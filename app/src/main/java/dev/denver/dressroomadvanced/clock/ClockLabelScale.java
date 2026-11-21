package dev.denver.dressroomadvanced;

final class ClockLabelScale {
    static final int MIN = 50;
    static final int DEFAULT = 100;
    static final int MAX = 200;
    static final String DATE = "dateSizePercent";
    static final String WEATHER = "weatherSizePercent";

    private ClockLabelScale() {}

    static int clamp(int percent) {
        return Math.max(MIN, Math.min(MAX, percent));
    }

    static float growth(float available, float base, float requested) {
        if (requested <= base || requested <= available) return 1f;
        return Math.max(0f, (available - base) / (requested - base));
    }

    static float fit(float base, float requested, float growth) {
        return requested <= base ? requested : base + (requested - base) * growth;
    }
}
