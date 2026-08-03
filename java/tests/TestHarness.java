/**
 * A deliberately tiny, dependency-free test harness. Pulling in JUnit
 * would need Maven Central, which isn't part of this sandbox's (or
 * necessarily every reader's) network allowlist -- plain assertions and
 * a runner class cover everything needed here.
 *
 * Deliberately declared with NO `package` statement: it lives in the
 * same (default/unnamed) package as CasCounter, SpscRingBuffer, etc.,
 * which is what lets the tests call their package-private helper
 * methods directly instead of needing everything made public just for
 * testability.
 */
public class TestHarness {
    private int passed = 0;
    private int failed = 0;

    public void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    public void checkEquals(String name, Object expected, Object actual) {
        boolean ok = (expected == null) ? (actual == null) : expected.equals(actual);
        check(name, ok);
        if (!ok) {
            System.out.println("         expected: " + expected + ", actual: " + actual);
        }
    }

    /** Returns the number of failures (0 = all passed). */
    public int summary(String suiteName) {
        System.out.println(suiteName + ": " + passed + " passed, " + failed + " failed");
        return failed;
    }
}
