package dev.denver.dressroomadvanced;

import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

record ClockLabelPosition(int x, int y, int rotation) {
    static final String DATE = "datePosition";
    static final String WEATHER = "weatherPosition";
    static final String GROUPED = "dateWeatherGrouped";
    static final ClockLabelPosition DEFAULT = new ClockLabelPosition(50, 20, 0);
    private static final String[] KEYS = {DATE, WEATHER};

    ClockLabelPosition {
        x = clamp(x, 100);
        y = clamp(y, 100);
        rotation = clamp(rotation, 360);
    }

    record Frame(float centerX, float centerY, float scale) {}

    Frame frame(float width, float height, float availableWidth, float availableHeight,
                float snapDistance) {
        double angle = Math.toRadians(rotation);
        float sin = (float) Math.abs(Math.sin(angle));
        float cos = (float) Math.abs(Math.cos(angle));
        float rotatedWidth = width * cos + height * sin;
        float rotatedHeight = width * sin + height * cos;
        // Fit the rotated drawing bounds so the edge gap is measured from the visible label.
        float scale = Math.min(1f, Math.min(availableWidth / rotatedWidth,
                availableHeight / rotatedHeight));
        float halfWidth = rotatedWidth * scale / 2f;
        float halfHeight = rotatedHeight * scale / 2f;
        return new Frame(
                center(x, availableWidth, halfWidth, snapDistance),
                center(y, availableHeight, halfHeight, snapDistance), scale);
    }

    // Convert a dragged center back to normalized travel, including the rotated bounds.
    ClockLabelPosition moved(float cx, float cy, float w, float h, float aw, float ah) {
        double a = Math.toRadians(rotation);
        float c = (float) Math.abs(Math.cos(a)), s = (float) Math.abs(Math.sin(a));
        Frame f = frame(w, h, aw, ah, 0);
        return new ClockLabelPosition(progress(cx, aw, (w*c+h*s)*f.scale()),
                progress(cy, ah, (w*s+h*c)*f.scale()), rotation);
    }

    static int progress(float center, float length, float labelLength) {
        float travel = length - labelLength;
        return travel > 0 ? clamp(Math.round(100f * (center - labelLength / 2f) / travel), 100)
                : 50;
    }

    private static float center(int progress, float length, float half, float snapDistance) {
        float travel = Math.max(0f, length - half * 2f);
        float offset = travel * progress / 100f;
        if (progress < 50 && offset < snapDistance) return half;
        if (progress > 50 && travel - offset < snapDistance) return length - half;
        if (Math.abs(offset - travel / 2f) < snapDistance) return length / 2f;
        return half + offset;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    static ClockLabelPosition from(Bundle settings, String key) {
        int[] values = settings == null ? null : settings.getIntArray(key);
        if (values == null || values.length != 3) return null;
        return new ClockLabelPosition(values[0], values[1], values[2]);
    }

    static void put(Bundle settings, String key, ClockLabelPosition position) {
        if (position != null)
            settings.putIntArray(key, new int[] {position.x, position.y, position.rotation});
    }

    static void save(JSONObject profile, Bundle settings) throws JSONException {
        profile.put(GROUPED, settings.getBoolean(GROUPED));
        for (String key : KEYS) {
            ClockLabelPosition position = from(settings, key);
            if (position == null) continue;
            profile.put(key, new JSONArray().put(position.x).put(position.y).put(position.rotation));
        }
    }

    static void load(JSONObject profile, Bundle settings) {
        settings.putBoolean(GROUPED, profile.optBoolean(GROUPED, false));
        for (String key : KEYS) {
            JSONArray values = profile.optJSONArray(key);
            if (values == null || values.length() != 3) continue;
            int x = values.optInt(0, -1);
            int y = values.optInt(1, -1);
            int rotation = values.optInt(2, -1);
            if (x >= 0 && y >= 0 && rotation >= 0)
                put(settings, key, new ClockLabelPosition(x, y, rotation));
        }
    }
}
