package dev.denver.dressroomadvanced;

import android.text.Layout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

final class ClockLabelLayout {
    private final Map<View, TextSize> textSizes = new WeakHashMap<>();
    private final Map<View, ImageSize> imageSizes = new WeakHashMap<>();

    private static final class TextSize {
        float original, applied;

        TextSize(float size) {
            original = applied = size;
        }
    }

    private static final class ImageSize {
        final int width, height, baseWidth, baseHeight;
        int appliedWidth, appliedHeight;

        ImageSize(View view, ViewGroup.LayoutParams params) {
            width = appliedWidth = params.width;
            height = appliedHeight = params.height;
            baseWidth = width >= 0 ? width : view.getMeasuredWidth();
            baseHeight = height >= 0 ? height : view.getMeasuredHeight();
        }
    }

    private record Part(
            View view, ArrayList<Part> children, boolean horizontal,
            float fixed, float content, float base, float requested, float scale, int margins) {}

    void apply(View clock, int date, int weather) {
        if (date == ClockLabelScale.DEFAULT && weather == ClockLabelScale.DEFAULT) {
            restore();
            return;
        }
        if (clock.getWidth() <= 0) return;
        Part root = measure(clock, 0f, date / 100f, weather / 100f);
        if (root != null) layout(root, clock.getWidth());
    }

    private Part measure(View view, float scale, float date, float weather) {
        if (view.getVisibility() == View.GONE) return null;
        String entry = DateEffects.entryName(view);
        if (DateEffects.isDate(entry)) scale = date;
        if ("common_weather_container".equals(entry)) scale = weather;
        int margins = margins(view);
        if (view instanceof TextView text && scale > 0f) {
            float size = text.getTextSize();
            TextSize stock = textSizes.computeIfAbsent(text, item -> new TextSize(size));
            if (size != stock.applied) stock.original = size;
            TextPaint paint = new TextPaint(text.getPaint());
            paint.setTextSize(stock.original);
            float width = Layout.getDesiredWidth(text.getText(), paint);
            int padding = text.getCompoundPaddingLeft() + text.getCompoundPaddingRight();
            return leaf(view, padding, width, scale, margins);
        }
        if (view instanceof ImageView && scale > 0f) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && params.width != ViewGroup.LayoutParams.MATCH_PARENT
                    && params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                ImageSize stock = imageSizes.get(view);
                if (stock == null || stock.appliedWidth != params.width || stock.appliedHeight != params.height) {
                    stock = new ImageSize(view, params);
                    if (stock.baseWidth > 0 && stock.baseHeight > 0) imageSizes.put(view, stock);
                }
                if (stock.baseWidth > 0 && stock.baseHeight > 0)
                    return leaf(view, 0, stock.baseWidth, scale, margins);
            }
        }
        if (!(view instanceof ViewGroup group)) return null;
        boolean horizontal = group instanceof LinearLayout row
                ? row.getOrientation() == LinearLayout.HORIZONTAL
                : "common_date_container".equals(entry);
        ArrayList<Part> children = new ArrayList<>();
        float fixed = group.getPaddingLeft() + group.getPaddingRight();
        float base = 0, requested = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            Part part = measure(child, scale, date, weather);
            if (part == null) {
                if (horizontal) fixed += child.getMeasuredWidth() + margins(child);
                continue;
            }
            children.add(part);
            float stock = Math.min(part.base(), part.requested()) + part.margins();
            float desired = part.requested() + part.margins();
            base = horizontal ? base + stock : Math.max(base, stock);
            requested = horizontal ? requested + desired : Math.max(requested, desired);
        }
        if (children.isEmpty()) return null;
        return new Part(group, children, horizontal, fixed, 0, fixed + base,
                fixed + requested, scale, margins);
    }

    private static Part leaf(View view, float fixed, float content, float scale, int margins) {
        return new Part(view, null, false, fixed, content, fixed + content,
                fixed + content * scale, scale, margins);
    }

    private void layout(Part part, float available) {
        if (part.children() == null) {
            float scale = part.content() <= 0 ? part.scale()
                    : Math.min(part.scale(), Math.max(1f, available - part.fixed()) / part.content());
            if (part.view() instanceof TextView text) {
                ViewGroup.LayoutParams params = text.getLayoutParams();
                float maxWidth = text.getMaxWidth() > 0 ? text.getMaxWidth() : available;
                if (params != null && params.width > 0) maxWidth = Math.min(maxWidth, params.width);
                if (part.content() > 0)
                    scale = Math.min(scale, Math.max(1f, maxWidth - part.fixed()) / part.content());
                applyText(text, scale);
            } else {
                applyImage(part.view(), scale);
            }
            return;
        }
        float space = Math.max(0, available - part.fixed());
        float growth = ClockLabelScale.growth(available, part.base(), part.requested());
        for (Part child : part.children()) {
            float width = space - child.margins();
            if (part.horizontal()) {
                width = ClockLabelScale.fit(child.base(), child.requested(), growth);
                if (space < part.base() - part.fixed()) {
                    float base = Math.min(child.base(), child.requested());
                    width = (base + child.margins()) * space / (part.base() - part.fixed())
                            - child.margins();
                }
            } else {
                width = Math.min(width, sideSpace((ViewGroup) part.view(), child.view(), width));
            }
            layout(child, Math.max(0, width));
        }
    }

    private static float sideSpace(ViewGroup parent, View view, float available) {
        if (parent instanceof LinearLayout || view.getWidth() == 0) return available;
        int left = parent.getPaddingLeft();
        int right = Math.round(available) + left;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == view || sibling.getVisibility() != View.VISIBLE
                    || sibling.getBottom() <= view.getTop() || sibling.getTop() >= view.getBottom())
                continue;
            if (sibling.getRight() <= view.getLeft()) left = Math.max(left, sibling.getRight());
            if (sibling.getLeft() >= view.getRight()) right = Math.min(right, sibling.getLeft());
        }
        return right - left;
    }

    private void applyText(TextView text, float scale) {
        TextSize stock = textSizes.get(text);
        float target = scale == 1f ? stock.original : Math.max(1f, (float) Math.floor(stock.original * scale));
        if (text.getTextSize() != target) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, target);
        stock.applied = target;
    }

    private void applyImage(View view, float scale) {
        ImageSize stock = imageSizes.get(view);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        int width = scale == 1f ? stock.width : Math.max(1, (int) (stock.baseWidth * scale));
        int height = scale == 1f ? stock.height : Math.max(1, (int) (stock.baseHeight * scale));
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }
        stock.appliedWidth = width;
        stock.appliedHeight = height;
    }

    void restore() {
        textSizes.forEach((view, stock) -> {
            TextView text = (TextView) view;
            if (text.getTextSize() == stock.applied) applyText(text, 1f);
        });
        imageSizes.forEach((view, stock) -> {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && params.width == stock.appliedWidth && params.height == stock.appliedHeight)
                applyImage(view, 1f);
        });
        textSizes.clear();
        imageSizes.clear();
    }

    private static int margins(View view) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)
            return params.leftMargin + params.rightMargin;
        return 0;
    }
}
