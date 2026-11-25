package dev.denver.dressroomadvanced;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
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

    private ClockLabelPlacement() {}

    static void bind(View clock, ClockLabelPosition date, ClockLabelPosition weather, boolean preview) {
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
        boolean changed = !Objects.equals(old.date, date) || !Objects.equals(old.weather, weather);
        old.date = date;
        old.weather = weather;
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
        for (View view = clock; view != null;
                view = view.getParent() instanceof View parent ? parent : null) {
            if (!(view instanceof ViewGroup group)) continue;
            String name = DateEffects.entryName(view);
            if (preview && "preview_single_view_root".equals(name)) return group;
            if (!preview && ("keyguard_root_view".equals(name)
                    || view.getClass().getName().equals("com.samsung.android.app.facewidget.SecKeyguardStatusView")))
                return group;
        }
        return null;
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
        boolean blocked, drawingDate;
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
                nativeScale = preview ? 1f : ClockLabelSizing.nativeScale(clock);
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
            if (host != null) host.getOverlay().remove(this);
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
            if (host == null || blocked || opacity <= 0) return;
            safeBounds(host, usable);
            if (usable.isEmpty()) return;
            int layer = opacity < 1f
                    ? canvas.saveLayerAlpha(usable, Math.round(opacity * 255)) : canvas.save();
            canvas.clipRect(usable);
            drawLabel(canvas, dateLabel, date, dateScale, 6 * host.getResources().getDisplayMetrics().density * hostScale);
            drawLabel(canvas, weatherLabel, weather, weatherScale, 0);
            canvas.restoreToCount(layer);
        }

        private void drawLabel(Canvas canvas, Label label, ClockLabelPosition position,
                               float scale, float snap) {
            if (position == null || label.width <= 0 || label.height <= 0 || scale <= 0) return;
            ClockLabelPosition.Frame frame = position.frame(label.width * scale, label.height * scale,
                    usable.width(), usable.height(), snap);
            int save = canvas.save();
            canvas.translate(usable.left + frame.centerX(), usable.top + frame.centerY());
            canvas.rotate(position.rotation());
            canvas.scale(scale * frame.scale(), scale * frame.scale());
            canvas.translate(-label.width / 2f, -label.height / 2f);
            label.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
