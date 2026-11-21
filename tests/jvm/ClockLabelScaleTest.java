package dev.denver.dressroomadvanced;

public final class ClockLabelScaleTest {
    private static int checks;

    private static void equal(float expected, float actual) {
        if (Math.abs(expected - actual) > .001f)
            throw new AssertionError("Expected " + expected + " but got " + actual);
        checks++;
    }

    public static void main(String[] args) {
        equal(50, ClockLabelScale.clamp(0));
        equal(100, ClockLabelScale.clamp(100));
        equal(175, ClockLabelScale.clamp(175));
        equal(200, ClockLabelScale.clamp(300));
        equal(1, ClockLabelScale.growth(240, 120, 200));
        equal(1, ClockLabelScale.growth(80, 100, 100));
        equal(0, ClockLabelScale.growth(100, 120, 180));

        float growth = ClockLabelScale.growth(170, 120, 220);
        equal(.5f, growth);
        equal(90, ClockLabelScale.fit(70, 110, growth));
        equal(80, ClockLabelScale.fit(50, 110, growth));
        equal(35, ClockLabelScale.fit(70, 35, growth));
        System.out.println("Clock label scale: " + checks + " checks passed");
    }
}
