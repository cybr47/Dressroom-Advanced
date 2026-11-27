package dev.denver.dressroomadvanced;

public final class ColonAlignmentTest {
    private static void expect(float expected, float actual) {
        if (Math.abs(expected - actual) > .001f || !Float.isFinite(actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        expect(108f, ColonAlignmentFix.correction(313, 741, 312, 526, 1));
        expect(0f, ColonAlignmentFix.correction(313, 741, 420, 634, 1));
        expect(-20f, ColonAlignmentFix.correction(0, 100, 60, 80, 1));
        expect(54f, ColonAlignmentFix.correction(313, 741, 312, 526, 2));
        expect(0f, ColonAlignmentFix.correction(0, 0, 10, 20, 1));
        expect(0f, ColonAlignmentFix.correction(0, 100, 10, 20, 0));
        expect(0f, ColonAlignmentFix.correction(0, 100, 500, 520, 1));
        expect(0f, ColonAlignmentFix.correction(Float.NaN, 100, 10, 20, 1));
        System.out.println("Imported colon alignment geometry passed");
    }
}
