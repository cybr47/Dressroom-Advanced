package dev.denver.dressroomadvanced;

import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Align the separate colon glyph to the actual ink of imported clock digits. */
final class ColonAlignmentFix {
    private static final Map<View, State> states = new WeakHashMap<>();
    private static final String PATH_METHOD = "getPathForBlur";
    private static final ClassValue<Optional<Method>> outlines = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                Method method = type.getDeclaredMethod(PATH_METHOD);
                if (!Path.class.isAssignableFrom(method.getReturnType())) return Optional.empty();
                method.setAccessible(true);
                return Optional.of(method);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return Optional.empty();
            }
        }
    };

    private ColonAlignmentFix() {}

    static void watch(View clock, boolean enabled) {
        if (!enabled) {
            State state = states.remove(clock);
            if (state != null) {
                clock.removeOnAttachStateChangeListener(state);
                state.stop();
            }
            return;
        }
        if (states.containsKey(clock)) return;
        State state = new State(clock);
        states.put(clock, state);
        clock.addOnAttachStateChangeListener(state);
        if (clock.isAttachedToWindow()) state.start(clock);
    }

    // Coordinates are in the same space; a bad or missing outline must not move a view.
    static float correction(float digitTop, float digitBottom, float colonTop, float colonBottom,
            float parentScale) {
        if (!(digitBottom > digitTop && colonBottom > colonTop && parentScale > 0f)) return 0f;
        float height = digitBottom - digitTop;
        float delta = (digitTop + height / 2f) - (colonTop + (colonBottom - colonTop) / 2f);
        if (!Float.isFinite(delta) || Math.abs(delta) > height) return 0f;
        float offset = delta / parentScale;
        return Float.isFinite(offset) ? offset : 0f;
    }

    private static final class State implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        final WeakReference<View> clock;
        WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
        WeakReference<TextView> adjusted = new WeakReference<>(null);
        final Matrix matrix = new Matrix();
        final RectF digits = new RectF(), scratch = new RectF(), colonInk = new RectF();
        final float[] axis = new float[2];
        final int normalGroup, stretchGroup, normalColon, stretchColon;
        final int[] normalDigits, stretchDigits;
        float baseTranslation, appliedTranslation;
        long lastSignature = Long.MIN_VALUE;
        int remainingSamples;

        State(View view) {
            clock = new WeakReference<>(view);
            normalGroup = id(view, "common_time_area");
            stretchGroup = id(view, "common_stretch_time_area");
            normalColon = id(view, "common_colon");
            stretchColon = id(view, "common_stretch_colon");
            normalDigits = new int[] {
                id(view, "common_minute10th"), id(view, "common_minute1st"),
                id(view, "common_minute")
            };
            stretchDigits = new int[] {
                id(view, "common_stretch_minute10th"), id(view, "common_stretch_minute1st")
            };
        }

        void start(View view) {
            ViewTreeObserver tree = view.getViewTreeObserver();
            if (observer.get() == tree) return;
            stop();
            observer = new WeakReference<>(tree);
            tree.addOnPreDrawListener(this);
        }

        void stop() {
            ViewTreeObserver tree = observer.get();
            if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(this);
            observer.clear();
            restore();
        }

        @Override public void onViewAttachedToWindow(View view) { start(view); }
        @Override public void onViewDetachedFromWindow(View view) { stop(); }

        private void restore() {
            TextView old = adjusted.get();
            if (old != null && Math.abs(old.getTranslationY() - appliedTranslation) < .01f)
                old.setTranslationY(baseTranslation);
            adjusted.clear();
            lastSignature = Long.MIN_VALUE;
            remainingSamples = 0;
        }

        @Override
        public boolean onPreDraw() {
            View root = clock.get();
            if (root == null) {
                stop();
                return true;
            }
            if (!root.isShown() || root.isLayoutRequested()) return true;
            View group = find(root, normalGroup);
            TextView colon = text(group, normalColon);
            int[] digitIds = normalDigits;
            if (colon == null) {
                group = find(root, stretchGroup);
                colon = text(group, stretchColon);
                digitIds = stretchDigits;
            }
            if (colon == null || !isColon(colon) || !(colon.getParent() instanceof View parent)) {
                restore();
                return true;
            }
            if (adjusted.get() != colon) {
                restore();
                adjusted = new WeakReference<>(colon);
                baseTranslation = appliedTranslation = colon.getTranslationY();
            }
            // Native animation or relayout may replace the translation; do not accumulate offsets.
            if (Math.abs(colon.getTranslationY() - appliedTranslation) >= .01f) {
                baseTranslation = appliedTranslation = colon.getTranslationY();
                lastSignature = Long.MIN_VALUE;
            }

            long signature = signature(colon, baseTranslation);
            for (int id : digitIds) {
                TextView digit = text(group, id);
                if (digit != null) signature = 31 * signature + signature(digit, digit.getTranslationY());
            }
            if (signature != lastSignature) {
                lastSignature = signature;
                // Native paths can still contain the previous font until the next draw.
                remainingSamples = 2;
            }
            if (remainingSamples == 0) return true;
            remainingSamples--;

            digits.setEmpty();
            for (int id : digitIds) {
                TextView digit = text(group, id);
                if (digit != null && digit.getTypeface() == colon.getTypeface()
                        && ink(digit, scratch)) digits.union(scratch);
            }
            if (!digits.isEmpty() && ink(colon, colonInk)) {
                matrix.reset();
                parent.transformMatrixToGlobal(matrix);
                axis[0] = 0f;
                axis[1] = 1f;
                matrix.mapVectors(axis);
                // The vertical row and rotated glyph layouts have different positioning contracts.
                if (axis[1] > 0f && Math.abs(axis[0]) < .001f) {
                    float offset = correction(digits.top, digits.bottom,
                            colonInk.top, colonInk.bottom, axis[1]);
                    float next = colon.getTranslationY() + offset;
                    if (Math.abs(offset) > .25f && Float.isFinite(next)) {
                        colon.setTranslationY(next);
                        appliedTranslation = next;
                    }
                }
            }
            if (remainingSamples > 0) root.postInvalidateOnAnimation();
            return true;
        }

        private boolean ink(TextView view, RectF out) {
            try {
                Method method = outlines.get(view.getClass()).orElse(null);
                if (method == null || view.isLayoutRequested()) return false;
                Object result = method.invoke(view);
                if (!(result instanceof Path path) || path.isEmpty()) return false;
                path.computeBounds(out, true);
                matrix.reset();
                view.transformMatrixToGlobal(matrix);
                matrix.mapRect(out);
                return !out.isEmpty() && Float.isFinite(out.top) && Float.isFinite(out.bottom);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return false;
            }
        }
    }

    private static int id(View view, String name) {
        Resources resources = view.getResources();
        return resources.getIdentifier(name, "id", view.getContext().getPackageName());
    }

    private static View find(View root, int id) {
        if (root == null || id == 0) return null;
        View view = root.findViewById(id);
        return view != null && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0
                ? view : null;
    }

    private static TextView text(View group, int id) {
        View view = find(group, id);
        return view instanceof TextView tv ? tv : null;
    }

    private static boolean isColon(TextView view) {
        String text = String.valueOf(view.getText());
        return ":".equals(text) || "\u2236".equals(text);
    }

    private static long signature(TextView view, float translation) {
        long key = System.identityHashCode(view);
        key = 31 * key + System.identityHashCode(view.getTypeface());
        key = 31 * key + String.valueOf(view.getText()).hashCode();
        key = 31 * key + view.getWidth();
        key = 31 * key + view.getHeight();
        key = 31 * key + view.getTop();
        key = 31 * key + view.getPaddingTop();
        key = 31 * key + view.getPaddingBottom();
        key = 31 * key + Float.floatToIntBits(view.getTextSize());
        key = 31 * key + Float.floatToIntBits(view.getTextScaleX());
        key = 31 * key + Float.floatToIntBits(view.getScaleY());
        key = 31 * key + Float.floatToIntBits(translation);
        String axes = view.getFontVariationSettings();
        return 31 * key + (axes == null ? 0 : axes.hashCode());
    }
}
