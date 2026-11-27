package dev.denver.dressroomadvanced;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

final class ClockLabelPlacement {
    private static final Map<View, State> states = new WeakHashMap<>();
    private static final Map<View, Integer> visibility = new WeakHashMap<>();

    private static boolean touchInstalled;
    private static final Map<View, State> previewHosts = new WeakHashMap<>();
    interface Editor {
        boolean active();
        void selected(boolean weather);
        void moved(boolean weather, ClockLabelPosition position, boolean finished);
    }

    private ClockLabelPlacement() {}

    // Drawable overlays do not receive touches. Only the matching preview host intercepts boxes.
    static synchronized void installEditor(DressroomModule module) {
        if (touchInstalled) return;
        try {
            var method = ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            module.hook(method).setId("dressroom-advanced.detached-label-touch")
                    .intercept(chain -> {
                        State state = previewHosts.get(chain.getThisObject());
                        if (state != null && state.touch((MotionEvent) chain.getArg(0))) return true;
                        return chain.proceed();
                    });
            module.deoptimize(method);
            touchInstalled = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.report("Detached label gestures unavailable", error);
        }
    }

    static void editor(View clock, Editor editor, boolean weather) {
        State state = states.get(clock);
        if (state != null && state.preview) {
            state.editor = editor;
            if (state.host != null) previewHosts.put(state.host, state);
            state.selectedWeather = weather;
            state.invalidateSelf();
        }
    }

    static void invalidatePreviews() {
        for (State state : new ArrayList<>(states.values()))
            if (state.preview) state.invalidateSelf();
    }

    static ClockLabelPosition splitPosition(View clock, boolean weather, ClockLabelPosition fallback) {
        State state = states.get(clock);
        if (state != null && state.grouped && state.host != null) {
            Box box = weather ? state.weatherBox : state.dateBox;
            if (box.width > 0 && box.height > 0 && !state.usable.isEmpty())
                return fallback.moved(box.x-state.usable.left, box.y-state.usable.top,
                        box.width, box.height, state.usable.width(), state.usable.height());
        }
        return new ClockLabelPosition(fallback.x() + (weather ? 10 : -10),
                fallback.y(), fallback.rotation());
    }

    static void bind(View clock, ClockLabelPosition date, ClockLabelPosition weather, boolean preview) {
        bind(clock, date, weather, preview, false);
    }

    static void bind(View clock, ClockLabelPosition date, ClockLabelPosition weather, boolean preview, boolean grouped) {
        State old = states.get(clock);
        if (!preview && old != null && old.preview) return;
        if (date == null && weather == null) {
            clear(clock);
            return;
        }
        if (old == null || old.preview != preview) {
            clear(clock);
            old = new State(clock, preview);
            states.put(clock, old);
            clock.addOnAttachStateChangeListener(old);
        }
        if ((old.date == null) != (date == null) || (old.weather == null) != (weather == null))
            ClockLabelSizing.restore(clock);
        boolean changed = !Objects.equals(old.date, date) || !Objects.equals(old.weather, weather) || old.grouped != grouped;
        old.date = date;
        old.weather = weather;
        old.grouped = grouped;
        if (changed) old.invalidateSelf();
    }

    static boolean hasPosition(View clock) {
        return states.containsKey(clock);
    }

    static boolean dateDetached(View clock) {
        State state = states.get(clock);
        return state != null && state.drawingDate;
    }

    static void clear(View clock) {
        State state = states.remove(clock);
        if (state != null) {
            clock.removeOnAttachStateChangeListener(state);
            state.detach();
        }
    }

    static void resetPreviews() {
        for (View clock : new ArrayList<>(states.keySet())) {
            State state = states.get(clock);
            if (state != null && state.preview) clear(clock);
        }
    }

    static int visibility(View view) {
        int current = view.getVisibility();
        return current == View.GONE ? visibility.getOrDefault(view, current) : current;
    }

    static void setVisibility(View view, int value) {
        if (visibility.containsKey(view)) visibility.put(view, value);
        else if (view.getVisibility() != value) view.setVisibility(value);
    }

    static void beforeNativeVisibilityChange(View clock) {
        State state = states.get(clock);
        if (state != null) state.restoreViews();
    }

    static void apply(View clock, boolean hideDate, View dateRow, View weather, boolean measuring) {
        State state = states.get(clock);
        if (state != null) state.apply(clock, hideDate, dateRow, weather, measuring);
    }

    static ClockLabelPosition initialPosition(View clock, boolean weather) {
        ViewGroup host = host(clock, true);
        if (host == null || host.getWidth() == 0 || host.getHeight() == 0)
            return ClockLabelPosition.DEFAULT;
        ArrayList<View> views = new ArrayList<>();
        if (weather) {
            View nativeWeather = weatherView(clock);
            if (nativeWeather != null && visibility(nativeWeather) == View.VISIBLE)
                views.add(nativeWeather);
        } else {
            collectDate(clock, views);
        }
        Matrix hostInverse = new Matrix();
        Matrix transform = new Matrix();
        host.transformMatrixToGlobal(transform);
        if (!transform.invert(hostInverse)) return ClockLabelPosition.DEFAULT;
        RectF combined = new RectF();
        for (View label : views) {
            if (label.getWidth() <= 0 || label.getHeight() <= 0) continue;
            transform.reset();
            label.transformMatrixToGlobal(transform);
            RectF bounds = new RectF(0, 0, label.getWidth(), label.getHeight());
            transform.mapRect(bounds);
            hostInverse.mapRect(bounds);
            combined.union(bounds);
        }
        if (combined.isEmpty()) return ClockLabelPosition.DEFAULT;
        RectF usable = new RectF();
        safeBounds(host, usable);
        return new ClockLabelPosition(
                ClockLabelPosition.progress(combined.centerX() - usable.left, usable.width(), combined.width()),
                ClockLabelPosition.progress(combined.centerY() - usable.top, usable.height(), combined.height()), 0);
    }

    private static ViewGroup host(View clock, boolean preview) {
        ViewGroup fallback = null;
        for (View view = clock; view != null;
                view = view.getParent() instanceof View parent ? parent : null) {
            if (!(view instanceof ViewGroup group)) continue;
            String name = DateEffects.entryName(view);
            if (preview && "preview_single_view_root".equals(name)) return group;
            if (!preview && "keyguard_root_view".equals(name)) return group;
            if (!preview && view.getClass().getName().equals(
                    "com.samsung.android.app.facewidget.SecKeyguardStatusView")) fallback = group;
        }
        return fallback;
    }

    private static View weatherView(View view) {
        if (isWeather(view)) return view;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = weatherView(group.getChildAt(i));
                if (found != null) return found;
            }
        return null;
    }

    static boolean isWeather(View view) {
        return isWeather(view, DateEffects.entryName(view));
    }

    static boolean isWeather(View view, String entry) {
        return "common_weather_container".equals(entry)
                || "common_weather_container".equals(view.getTag());
    }

    private static void collectDate(View view, List<View> parts) {
        if (visibility(view) != View.VISIBLE || isWeather(view)) return;
        if (view instanceof TextView && DateEffects.isDate(DateEffects.entryName(view))) parts.add(view);
        else if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) collectDate(group.getChildAt(i), parts);
    }

    private static void collectWeather(View view, List<View> parts) {
        if (visibility(view) != View.VISIBLE) return;
        if (view instanceof ImageView || view instanceof TextView) parts.add(view);
        else if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) collectWeather(group.getChildAt(i), parts);
    }

    private static boolean hasVisibleContent(View view) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (!(view instanceof ViewGroup group)) return true;
        for (int i = 0; i < group.getChildCount(); i++)
            if (hasVisibleContent(group.getChildAt(i))) return true;
        return false;
    }

    private static float hostScale(View host) {
        int width = host.getResources().getDisplayMetrics().widthPixels;
        return width > 0 ? (float) host.getWidth() / width : 1f;
    }

    private static void safeBounds(ViewGroup host, RectF bounds) {
        float scale = hostScale(host);
        float margin = 8 * host.getResources().getDisplayMetrics().density * scale;
        WindowInsets insets = host.getRootWindowInsets();
        android.graphics.Insets cutout = insets == null ? android.graphics.Insets.NONE
                : insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
        bounds.set(margin + cutout.left * scale, margin + cutout.top * scale,
                host.getWidth() - margin - cutout.right * scale,
                host.getHeight() - margin - cutout.bottom * scale);
    }

    private static final class State extends Drawable implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        final WeakReference<View> clock;
        final boolean preview;
        final Set<View> suppressed = Collections.newSetFromMap(new WeakHashMap<>());
        final Label dateLabel = new Label();
        final Label weatherLabel = new Label();
        final RectF usable = new RectF();
        ClockLabelPosition date, weather;
        ViewGroup host;
        ViewTreeObserver observer;
        ClockTransitionBinding transition;
        boolean blocked, drawingDate, grouped, selectedWeather, dragging;
        Editor editor;
        final Box dateBox = new Box(), weatherBox = new Box(), groupBox = new Box();
        final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        int pointer = -1;
        float grabX, grabY;
        float nativeScale = 1f;
        float opacity, dateScale, weatherScale, hostScale;

        State(View clock, boolean preview) {
            this.clock = new WeakReference<>(clock);
            this.preview = preview;
            if (!preview) {
                try {
                    transition = ClockTransitionBinding.of(clock);
                } catch (RuntimeException | LinkageError ignored) {
                    // Older clock providers can omit the media and doze transition fields.
                }
            }
        }

        void apply(View clock, boolean hideDate, View dateRow, View weatherView, boolean measuring) {
            ViewGroup target = clock.isAttachedToWindow() ? host(clock, preview) : null;
            blocked = blocked(clock);
            if (target != host) {
                detach();
                if (target != null) observe(target);
            }
            if (blocked || (host == null && (!measuring || clock.isAttachedToWindow()))) {
                restoreViews();
                drawingDate = false;
                dateLabel.clear();
                weatherLabel.clear();
                invalidateSelf();
                return;
            }

            var dates = new ArrayList<View>();
            var weatherParts = new ArrayList<View>();
            if (date != null && !hideDate && clock instanceof ViewGroup group)
                for (int i = 0; i < group.getChildCount(); i++) collectDate(group.getChildAt(i), dates);
            if (weather != null && weatherView != null) collectWeather(weatherView, weatherParts);

            drawingDate = !dates.isEmpty();
            boolean drawingWeather = !weatherParts.isEmpty();
            if (host != null) {
                nativeScale = ClockLabelSizing.nativeScale(clock);
                dateLabel.bind(dates, false, false);
                boolean row = !(weatherView instanceof LinearLayout linear)
                        || linear.getOrientation() == LinearLayout.HORIZONTAL;
                weatherLabel.bind(weatherParts, row, row && weatherView != null
                        && weatherView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            }

            // Keep the source views attached so Samsung's style and weather updates can find them.
            Set<View> targets = new HashSet<>(dates);
            if (drawingWeather) targets.add(weatherView);
            for (View view : new ArrayList<>(suppressed)) {
                if (view != dateRow && !targets.contains(view)) restore(view);
            }
            for (View view : targets) suppress(view);
            if (dateRow != null) {
                boolean emptyRow = false;
                if (dateRow instanceof ViewGroup group && !targets.isEmpty()) {
                    emptyRow = true;
                    for (int i = 0; i < group.getChildCount(); i++)
                        if (hasVisibleContent(group.getChildAt(i))) emptyRow = false;
                }
                if (emptyRow) suppress(dateRow);
                else restore(dateRow);
            }
            if (host != null) refresh(clock);
        }

        private boolean blocked(View clock) {
            if (transition == null) return false;
            try {
                return transition.mediaExpanded.getBoolean(clock)
                        || transition.amount.getFloat(clock) > 0;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }

        private void observe(ViewGroup target) {
            host = target;
            if (preview && editor != null) previewHosts.put(target, this);
            target.getOverlay().add(this);
            observer = target.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }

        private void suppress(View view) {
            visibility.put(view, visibility(view));
            suppressed.add(view);
            if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        }

        private void restore(View view) {
            if (!suppressed.remove(view)) return;
            Integer original = visibility.remove(view);
            if (original != null && view.getVisibility() == View.GONE && original != View.GONE)
                view.setVisibility(original);
        }

        void restoreViews() {
            for (View view : new ArrayList<>(suppressed)) restore(view);
        }

        void detach() {
            restoreViews();
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            if (host != null) {
                if (previewHosts.get(host) == this) previewHosts.remove(host);
                host.getOverlay().remove(this);
            }
            dragging = false;
            pointer = -1;
            host = null;
            dateLabel.clear();
            weatherLabel.clear();
            drawingDate = false;
        }

        @Override public void onViewAttachedToWindow(View view) {
            DateEffects.apply(view);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            detach();
        }

        @Override public boolean onPreDraw() {
            View view = clock.get();
            if (view != null) {
                if (blocked(view) != blocked || sourceShownByProvider()) DateEffects.apply(view);
                else if (!blocked && host != null) refresh(view);
            }
            return true;
        }

        private boolean sourceShownByProvider() {
            // Some provider refreshes change a label's visibility directly.
            for (View source : suppressed)
                if (source.getVisibility() != View.GONE) return true;
            return false;
        }

        private void refresh(View clock) {
            float nextAlpha = clock.isShown() && !blocked ? 1f : 0f;
            for (View view = clock; view != null && view != host;
                    view = view.getParent() instanceof View parent ? parent : null)
                nextAlpha *= view.getAlpha();
            // Normalize the provider zoom equally in preview and runtime.
            nativeScale = ClockLabelSizing.nativeScale(clock);
            float nextHostScale = hostScale(host);
            float nextDateScale = ClockLabelSizing.detachedFactor(clock, false, nativeScale) * nextHostScale;
            float nextWeatherScale = ClockLabelSizing.detachedFactor(clock, true, nativeScale) * nextHostScale;
            boolean dateChanged = dateLabel.refresh();
            boolean weatherChanged = weatherLabel.refresh();
            if (getBounds().width() != host.getWidth() || getBounds().height() != host.getHeight())
                setBounds(0, 0, host.getWidth(), host.getHeight());
            if (dateChanged || weatherChanged || nextAlpha != opacity || nextDateScale != dateScale
                    || nextWeatherScale != weatherScale || nextHostScale != hostScale) {
                opacity = nextAlpha;
                dateScale = nextDateScale;
                weatherScale = nextWeatherScale;
                hostScale = nextHostScale;
                invalidateSelf();
            }
        }

        @Override public void draw(Canvas canvas) {
            dateBox.clear(); weatherBox.clear(); groupBox.clear();
            if (host == null || blocked || opacity <= 0) return;
            safeBounds(host, usable);
            if (usable.isEmpty()) return;
            int layer = opacity < 1f
                    ? canvas.saveLayerAlpha(usable, Math.round(opacity * 255)) : canvas.save();
            canvas.clipRect(usable);
            float snap = 6 * host.getResources().getDisplayMetrics().density * hostScale;
            if (grouped) drawGroup(canvas, snap);
            else {
                drawLabel(canvas, dateLabel, date, dateScale, snap, dateBox);
                drawLabel(canvas, weatherLabel, weather, weatherScale, snap, weatherBox);
            }
            canvas.restoreToCount(layer);
            if (editing()) {
                if (grouped) drawBorder(canvas, groupBox, true);
                else {
                    drawBorder(canvas, dateBox, !selectedWeather);
                    drawBorder(canvas, weatherBox, selectedWeather);
                }
            }
        }

        private void drawLabel(Canvas canvas, Label label, ClockLabelPosition position,
                               float scale, float snap, Box box) {
            if (position == null || label.width <= 0 || label.height <= 0 || scale <= 0) return;
            ClockLabelPosition.Frame frame = position.frame(label.width * scale, label.height * scale,
                    usable.width(), usable.height(), snap);
            box.set(usable.left + frame.centerX(), usable.top + frame.centerY(),
                    label.width * scale * frame.scale(), label.height * scale * frame.scale(), position.rotation());
            int save = canvas.save();
            canvas.translate(box.x, box.y);
            canvas.rotate(position.rotation());
            canvas.scale(scale * frame.scale(), scale * frame.scale());
            canvas.translate(-label.width / 2f, -label.height / 2f);
            label.draw(canvas);
            canvas.restoreToCount(save);
        }

        private boolean editing() { return preview && editor != null && editor.active(); }

        private void drawGroup(Canvas canvas, float snap) {
            ClockLabelPosition position = date != null ? date : weather;
            if (position == null) return;
            float dw = dateLabel.width * dateScale, dh = dateLabel.height * dateScale;
            float ww = weatherLabel.width * weatherScale, wh = weatherLabel.height * weatherScale;
            float gap = dw > 0 && ww > 0 ? 8 * host.getResources().getDisplayMetrics().density * hostScale : 0;
            float w = dw + ww + gap, h = Math.max(dh, wh);
            if (w <= 0 || h <= 0) return;
            ClockLabelPosition.Frame frame = position.frame(w, h, usable.width(), usable.height(), snap);
            groupBox.set(usable.left + frame.centerX(), usable.top + frame.centerY(),
                    w * frame.scale(), h * frame.scale(), position.rotation());
            int save = canvas.save();
            canvas.translate(groupBox.x, groupBox.y);
            canvas.rotate(position.rotation());
            canvas.scale(frame.scale(), frame.scale());
            if (dw > 0 && dh > 0) {
                groupBox.child(dateBox, (-w/2+dw/2)*frame.scale(), dw*frame.scale(), dh*frame.scale());
                int part = canvas.save();
                canvas.translate(-w/2, -dh/2);
                canvas.scale(dateScale, dateScale);
                dateLabel.draw(canvas);
                canvas.restoreToCount(part);
            }
            if (ww > 0 && wh > 0) {
                groupBox.child(weatherBox, (w/2-ww/2)*frame.scale(), ww*frame.scale(), wh*frame.scale());
                int part = canvas.save();
                canvas.translate(w/2-ww, -wh/2);
                canvas.scale(weatherScale, weatherScale);
                weatherLabel.draw(canvas);
                canvas.restoreToCount(part);
            }
            canvas.restoreToCount(save);
        }

        private void drawBorder(Canvas canvas, Box box, boolean selected) {
            if (box.width <= 0 || box.height <= 0) return;
            float dp = host.getResources().getDisplayMetrics().density * hostScale;
            int save = canvas.save();
            canvas.translate(box.x, box.y);
            canvas.rotate(box.rotation);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth((selected ? 2 : 1) * dp);
            border.setColor(selected ? 0xffffffff : 0x88ffffff);
            float pad = 4 * dp;
            canvas.drawRoundRect(-box.width/2-pad, -box.height/2-pad,
                    box.width/2+pad, box.height/2+pad, 6*dp, 6*dp, border);
            canvas.restoreToCount(save);
        }

        boolean touch(MotionEvent event) {
            int action = event.getActionMasked();
            if (!editing() || blocked || opacity <= 0) {
                if (dragging) { dragging = false; pointer = -1; return true; }
                return false;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                float x = event.getX(), y = event.getY();
                float pad = 12 * host.getResources().getDisplayMetrics().density * hostScale;
                boolean hitDate = dateBox.contains(x, y, pad), hitWeather = weatherBox.contains(x, y, pad);
                if (grouped ? !groupBox.contains(x, y, pad) : !hitDate && !hitWeather) return false;
                selectedWeather = !grouped && hitWeather && (!hitDate || selectedWeather);
                Box box = grouped ? groupBox : selectedWeather ? weatherBox : dateBox;
                grabX = x-box.x; grabY = y-box.y;
                pointer = event.getPointerId(0);
                dragging = true;
                boolean selected = selectedWeather;
                editor.selected(selected);
                selectedWeather = selected;
                invalidateSelf();
                return true;
            }
            if (!dragging) return false;
            int index = event.findPointerIndex(pointer);
            boolean finished = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || (action == MotionEvent.ACTION_POINTER_UP
                    && event.getPointerId(event.getActionIndex()) == pointer);
            if (index >= 0 && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
                Box box = grouped ? groupBox : selectedWeather ? weatherBox : dateBox;
                ClockLabelPosition old = selectedWeather ? weather : date;
                if (old == null) old = weather;
                if (old != null && box.width > 0 && box.height > 0) {
                    ClockLabelPosition next = old.moved(event.getX(index)-grabX-usable.left,
                            event.getY(index)-grabY-usable.top, box.width, box.height, usable.width(), usable.height());
                    if (grouped) date = weather = next;
                    else if (selectedWeather) weather = next;
                    else date = next;
                    editor.moved(selectedWeather, next, finished);
                    invalidateSelf();
                }
            }
            if (finished) {
                ClockLabelPosition current = selectedWeather ? weather : date;
                if (current == null) current = weather;
                if (current != null && action != MotionEvent.ACTION_UP)
                    editor.moved(selectedWeather, current, true);
                dragging = false;
                pointer = -1;
            }
            return true;
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // Drawing, hit testing and movement share the same rotated geometry.
    private static final class Box {
        float x, y, width, height;
        int rotation;
        void clear() { width = height = 0; }
        void set(float x, float y, float width, float height, int rotation) {
            this.x=x; this.y=y; this.width=width; this.height=height; this.rotation=rotation;
        }
        void child(Box target, float dx, float w, float h) {
            double a = Math.toRadians(rotation);
            target.set(x+dx*(float)Math.cos(a), y+dx*(float)Math.sin(a), w, h, rotation);
        }
        boolean contains(float px, float py, float pad) {
            if (width <= 0 || height <= 0) return false;
            double a = Math.toRadians(rotation);
            float dx=px-x, dy=py-y;
            return Math.abs(dx*Math.cos(a)+dy*Math.sin(a)) <= width/2+pad
                    && Math.abs(-dx*Math.sin(a)+dy*Math.cos(a)) <= height/2+pad;
        }
    }

    private static final class Label {
        final List<Part> parts = new ArrayList<>();
        boolean row, rtl;
        int width, height;

        void bind(List<View> sources, boolean row, boolean rtl) {
            this.row = row;
            this.rtl = rtl;
            if (parts.size() == sources.size()) {
                boolean same = true;
                for (int i = 0; i < sources.size(); i++)
                    if (parts.get(i).source != sources.get(i)) same = false;
                if (same) return;
            }
            clear();
            for (View source : sources) parts.add(new Part(source));
        }

        void clear() {
            parts.clear();
            width = height = 0;
        }

        boolean refresh() {
            int w = 0, h = 0;
            boolean changed = false;
            for (Part part : parts) {
                changed |= part.refresh();
                w = row ? w + part.width : Math.max(w, part.width);
                h = row ? Math.max(h, part.height) : h + part.height;
            }
            changed |= w != width || h != height;
            width = w;
            height = h;
            return changed;
        }

        void draw(Canvas canvas) {
            int offset = 0;
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(rtl ? parts.size() - 1 - i : i);
                if (part.width <= 0 || part.height <= 0) continue;
                int save = canvas.save();
                canvas.translate(row ? offset : (width - part.width) / 2f,
                        row ? (height - part.height) / 2f : offset);
                part.draw(canvas);
                canvas.restoreToCount(save);
                offset += row ? part.width : part.height;
            }
        }
    }

    private static final class Part {
        final View source;
        final Rect iconBounds = new Rect();
        TextPaint paint;
        CharSequence contents;
        Layout layout;
        Drawable icon, fallback;
        float fontSize, textScale, spacing;
        int color, width, height;
        android.graphics.Typeface typeface;

        Part(View source) {
            this.source = source;
        }

        boolean refresh() {
            if (source instanceof TextView text) {
                TextPaint nativePaint = text.getPaint();
                CharSequence value = text.getText();
                if (text.getTransformationMethod() != null)
                    value = text.getTransformationMethod().getTransformation(value, text);
                int nextColor = text.getCurrentTextColor();
                if (TextUtils.equals(value, contents) && fontSize == nativePaint.getTextSize()
                        && typeface == nativePaint.getTypeface() && color == nextColor
                        && textScale == nativePaint.getTextScaleX() && spacing == text.getLetterSpacing()
                        && (paint == null || paint.getShader() == nativePaint.getShader()))
                    return false;
                contents = value != null ? TextUtils.stringOrSpannedString(value) : "";
                fontSize = nativePaint.getTextSize();
                color = nextColor;
                typeface = nativePaint.getTypeface();
                textScale = nativePaint.getTextScaleX();
                spacing = text.getLetterSpacing();
                paint = new TextPaint(nativePaint);
                paint.setColor(color);
                width = (int) Math.ceil(Layout.getDesiredWidth(contents, paint));
                layout = width <= 0 ? null : StaticLayout.Builder
                        .obtain(contents, 0, contents.length(), paint, width)
                        .setIncludePad(false).build();
                height = layout != null ? layout.getHeight() : 0;
                return true;
            }
            if (source instanceof ImageView image) {
                Drawable next = image.getDrawable();
                Rect bounds = next == null ? null : next.getBounds();
                boolean laidOut = bounds != null && !bounds.isEmpty();
                int w = 0, h = 0;
                if (next != null) {
                    ViewGroup.LayoutParams params = image.getLayoutParams();
                    w = params != null && params.width > 0 ? params.width
                            : laidOut ? bounds.width() : next.getIntrinsicWidth();
                    h = params != null && params.height > 0 ? params.height
                            : laidOut ? bounds.height() : next.getIntrinsicHeight();
                }
                boolean changed = next != icon || width != w || height != h
                        || (laidOut && !iconBounds.equals(bounds));
                if (laidOut || next == null || w <= 0 || h <= 0) {
                    fallback = null;
                } else if ((fallback == null || changed) && next.getConstantState() != null) {
                    // A detached row can hide before Samsung assigns the icon's drawable bounds.
                    fallback = next.getConstantState().newDrawable(image.getResources()).mutate();
                    fallback.setBounds(0, 0, w, h);
                    fallback.setState(next.getState());
                    if (image.getImageTintList() != null) fallback.setTintList(image.getImageTintList());
                    if (next.getColorFilter() != null) fallback.setColorFilter(next.getColorFilter());
                    changed = true;
                }
                icon = next;
                width = w;
                height = h;
                if (laidOut) iconBounds.set(bounds);
                else iconBounds.setEmpty();
                return changed;
            }
            return false;
        }

        void draw(Canvas canvas) {
            if (layout != null) layout.draw(canvas);
            else if (fallback != null) fallback.draw(canvas);
            else if (icon != null && !iconBounds.isEmpty()) {
                canvas.scale((float) width / iconBounds.width(), (float) height / iconBounds.height());
                canvas.translate(-iconBounds.left, -iconBounds.top);
                icon.draw(canvas);
            }
        }
    }
}
