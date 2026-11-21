package dev.denver.dressroomadvanced;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ClockLabelLayoutProbe {
    private static final int DATE = 1001;
    private static final int WEATHER = 1002;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static int checks;

    private static void check(boolean expected, String message) {
        if (!expected) throw new AssertionError(message);
        checks++;
    }

    private static void layout(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static TextView label(Context context, String value, int size) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        text.setSingleLine();
        return text;
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> memory = Class.forName("com.android.internal.os.ApplicationSharedMemory");
        memory.getMethod("setInstance", memory)
                .invoke(null, memory.getMethod("create").invoke(null));
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        Context base = (Context) type.getMethod("getSystemContext").invoke(thread);
        Resources nativeResources = base.getResources();
        Resources resources = new Resources(nativeResources.getAssets(),
                nativeResources.getDisplayMetrics(), nativeResources.getConfiguration()) {
            @Override
            public String getResourceEntryName(int id) {
                if (id == DATE) return "common_date";
                if (id == WEATHER) return "common_weather_container";
                return super.getResourceEntryName(id);
            }
        };
        Context context = new ContextWrapper(base) {
            @Override
            public Resources getResources() {
                return resources;
            }
        };
        LinearLayout clock = new LinearLayout(context);
        clock.setPadding(4, 0, 4, 0);
        TextView date = label(context, "Saturday, 21 November", 20);
        date.setId(DATE);
        clock.addView(date, WRAP, WRAP);
        LinearLayout weather = new LinearLayout(context);
        weather.setId(WEATHER);
        ImageView icon = new ImageView(context);
        weather.addView(icon, 16, 16);
        TextView degrees = label(context, "26°", 20);
        weather.addView(degrees, WRAP, WRAP);
        clock.addView(weather, WRAP, WRAP);
        View time = new View(context);
        clock.addView(time, 120, 20);

        ClockLabelLayout sizing = new ClockLabelLayout();
        layout(clock, 700);
        sizing.apply(clock, 200, 50);
        layout(clock, 700);
        check(date.getTextSize() == 40 && degrees.getTextSize() == 10, "Independent scaling");
        check(icon.getLayoutParams().width == 8, "Weather icon follows weather text");
        sizing.apply(clock, 50, 200);
        layout(clock, 700);
        check(date.getTextSize() == 10 && degrees.getTextSize() == 40, "Reverse drag");
        check(icon.getLayoutParams().width == 32, "Icon scales from stock dimensions");

        layout(clock, 490);
        sizing.apply(clock, 200, 200);
        layout(clock, 490);
        float fitted = date.getTextSize();
        check(fitted > 20 && fitted < 40, "The longer date gets a width cap");
        check(date.getLayout().getLineWidth(0) <= date.getWidth(), "The date fits");
        check(time.getLeft() >= weather.getRight() && time.getRight() <= 486, "Time keeps its slot");
        sizing.apply(clock, 200, 200);
        check(date.getTextSize() == fitted, "Repeated layout preserves the same fitted text size");

        sizing.apply(clock, 100, 100);
        check(date.getTextSize() == 20 && degrees.getTextSize() == 20, "Native text sizes restored");
        check(icon.getLayoutParams().width == 16, "Native icon size restored");
        System.out.println("Clock label layout: " + checks + " checks passed");
        System.exit(0);
    }
}
