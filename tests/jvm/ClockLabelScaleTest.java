package dev.denver.dressroomadvanced;

public final class ClockLabelScaleTest {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void equal(float expected, float actual) {
        if (!(Math.abs(expected - actual) < .2f))
            throw new AssertionError("Expected " + expected + " but got " + actual);
        checks++;
    }

    private static ClockLabelFit.Size row(ClockLabelFit.Scales scales) {
        return new ClockLabelFit.Size(125 + 110 * scales.date() + 40 * scales.weather(), 50);
    }

    private static ClockLabelFit.Size stacked(ClockLabelFit.Scales scales) {
        return new ClockLabelFit.Size(
                Math.max(140, Math.max(100 * scales.date(), 58 * scales.weather())),
                115 + 20 * scales.date() + 14 * scales.weather());
    }

    public static void main(String[] args) {
        equal(50, ClockLabelScale.clamp(0));
        equal(100, ClockLabelScale.clamp(100));
        equal(175, ClockLabelScale.clamp(175));
        equal(200, ClockLabelScale.clamp(300));
        equal(0, ClockLabelScale.reference(0f, 100, 100));
        equal(1, ClockLabelScale.reference(0f, 110, 100));
        equal(1.35f, ClockLabelScale.reference(1.35f, 100, 100));

        float zoom = 1.6875f;
        float previewFactor = ClockLabelScale.labelFactor(1, zoom, 1);
        float appliedFactor = ClockLabelScale.labelFactor(1, 1, zoom);
        equal(20, 20 * previewFactor * zoom);
        equal(20, 20 * zoom * appliedFactor);
        equal(40, 20 * zoom * appliedFactor * 2);
        equal(20, 20 * ClockLabelScale.labelFactor(1, 1.125f, 1.5f) * 1.125f * 1.5f);
        equal(1, ClockLabelScale.labelFactor(1, Float.NaN, 0));
        check(!ClockLabelScale.labelLayoutEnabled(1f, true),
                "Expanded media player keeps Samsung clock geometry");
        check(ClockLabelScale.labelLayoutEnabled(1f, false),
                "Lock screen applies custom label layout");
        check(!ClockLabelScale.labelLayoutEnabled(0f, false),
                "Stock labels do not enter the layout scaling path");
        check(ClockLabelFit.preserveEditorCenter(
                        1f, 1f, false, false, true, true),
                "Stable editor label state preserves the clock center");
        check(!ClockLabelFit.preserveEditorCenter(
                        1f, 1f, false, true, true, true),
                "Hide-date state change does not reuse the old clock center");

        ClockLabelFit.Size walls = new ClockLabelFit.Size(420, 240);
        ClockLabelFit.Scales loose = ClockLabelFit.fit(walls, new ClockLabelFit.Scales(2, 1),
                1, ClockLabelScaleTest::row);
        equal(2, loose.date());
        equal(1, loose.weather());
        equal(385, row(loose).width());

        ClockLabelFit.Scales tight = ClockLabelFit.fit(new ClockLabelFit.Size(320, 80),
                new ClockLabelFit.Scales(2, 1), 1, ClockLabelScaleTest::row);
        equal((320 - 125 - 40) / 110f, tight.date());
        equal(1, tight.weather());
        equal(320, row(tight).width());

        ClockLabelFit.Scales crowded = ClockLabelFit.fit(new ClockLabelFit.Size(300, 80),
                new ClockLabelFit.Scales(2, 2), 1, ClockLabelScaleTest::row);
        equal(300, row(crowded).width());

        ClockLabelFit.Scales stacked = ClockLabelFit.fit(new ClockLabelFit.Size(170, 240),
                new ClockLabelFit.Scales(2, 2), 1, ClockLabelScaleTest::stacked);
        equal(1.7f, stacked.date());
        equal(2, stacked.weather());
        equal(170, stacked(stacked).width());
        ClockLabelFit.Scales shortBox = ClockLabelFit.fit(new ClockLabelFit.Size(400, 150),
                new ClockLabelFit.Scales(2, 1), 1, ClockLabelScaleTest::stacked);
        equal(1.05f, shortBox.date());
        equal(1, shortBox.weather());
        equal(150, stacked(shortBox).height());

        equal(60, ClockLabelFit.centeredStart(100, 80, 0, 260));
        equal(50, ClockLabelFit.centeredStart(100, 100, 0, 260));
        equal(10, ClockLabelFit.centeredStart(35, 80, 10, 260));
        equal(180, ClockLabelFit.centeredStart(250, 80, 10, 260));
        detachedBounds();
        ClockLabelPosition dragged = new ClockLabelPosition(50, 50, 90).moved(10, 360, 80, 20, 200, 400);
        check(dragged.equals(new ClockLabelPosition(0, 100, 90)), "Rotated drag uses visible edges");
        equal(100, new ClockLabelPosition(49, 50, 0).frame(80, 20, 200, 400, 5).centerX());
        System.out.println("Clock label scale and bounds: " + checks + " checks passed");
    }

    private static void detachedBounds() {
        ClockLabelPosition.Frame horizontal = new ClockLabelPosition(0, 100, 0)
                .frame(80, 20, 200, 400, 5);
        equal(40, horizontal.centerX());
        equal(390, horizontal.centerY());
        equal(1, horizontal.scale());

        ClockLabelPosition.Frame snapped = new ClockLabelPosition(2, 99, 90)
                .frame(80, 20, 200, 400, 5);
        equal(10, snapped.centerX());
        equal(360, snapped.centerY());
        ClockLabelPosition.Frame outsideSnap = new ClockLabelPosition(4, 97, 90)
                .frame(80, 20, 200, 400, 5);
        equal(17.2f, outsideSnap.centerX());
        equal(350.4f, outsideSnap.centerY());

        ClockLabelPosition.Frame rotated = new ClockLabelPosition(100, 0, 45)
                .frame(80, 20, 200, 400, 5);
        float rotatedHalf = (float) (50 / Math.sqrt(2));
        equal(200 - rotatedHalf, rotated.centerX());
        equal(rotatedHalf, rotated.centerY());
        ClockLabelPosition.Frame scaled = new ClockLabelPosition(100, 50, 90)
                .frame(600, 60, 200, 400, 5);
        equal(2f / 3f, scaled.scale());
        equal(180, scaled.centerX());
        equal(200, scaled.centerY());
        equal(50, ClockLabelPosition.progress(120, 240, 80));
        check(new ClockLabelPosition(-1, 120, 380).equals(new ClockLabelPosition(0, 100, 360)),
                "Saved positions stay in the slider range");
    }
}
