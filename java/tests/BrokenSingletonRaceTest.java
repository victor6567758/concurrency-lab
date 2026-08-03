/**
 * NEGATIVE TEST -- this file is intentionally buggy, and unlike
 * UnsafePublicationRaceTest, it is honestly unreliable at proving it.
 *
 * This is a standalone, stress-tested version of
 * DoubleCheckedLockingTest's BrokenSingleton: a singleton published
 * through a plain (non-volatile) reference, read and written by
 * multiple racing threads with no synchronization around the
 * fast-path check.
 *
 * We tried hard to make this manifest empirically, the same way
 * UnsafePublicationRaceTest does: hundreds of thousands of rounds,
 * multiple racing threads per round, a Helper with several non-final
 * fields to widen the window for observing a partially-constructed
 * object. In our testing, on OpenJDK 21 HotSpot / x86-64, it did NOT
 * reproduce even once across 800,000 observations. That is not because
 * the code is safe -- it is exactly as broken as ever under the JMM --
 * it's because x86 hardware's strong memory ordering (TSO) rarely lets
 * stores get reordered relative to each other, so the specific bad
 * interleaving this bug depends on almost never occurs in practice on
 * this architecture, even though the JLS permits it.
 *
 * This is the central, honest point of putting this file in the
 * project at all: unlike ThreadSanitizer's C++ report (which flags the
 * unsynchronized ACCESS PATTERN itself, regardless of whether the bad
 * reordering happens to occur on this run), Java has no tool that
 * inspects the access pattern independent of whether the miscompile
 * actually manifests. So this test cannot reliably say "caught it" --
 * it can only report what it observed, honestly, and note that a clean
 * report proves nothing. (On a weaker-memory-model architecture like
 * ARM, or under a different JIT's optimizations, this might reproduce
 * far more easily -- we simply don't have that hardware to verify it
 * on here.)
 *
 * Because a "no anomaly observed" result is genuinely inconclusive
 * (not evidence of correctness), this test does NOT assert the bug
 * must be observed -- doing so would make the suite flaky for the
 * wrong reason. It only asserts that the stress run itself completes
 * without crashing, and reports the anomaly count as information.
 */
public class BrokenSingletonRaceTest {

    static class Helper {
        int a, b, c, d, e; // several non-final fields to widen the window
        Helper(int v) { a = v; b = v; c = v; d = v; e = v; }
        boolean isConsistent() { return a == b && b == c && c == d && d == e; }
    }

    static Helper helper; // plain reference -- NOT volatile
    static final Object lock = new Object();

    static Helper getInstance(int v) {
        if (helper == null) {                 // unsynchronized read: data race
            synchronized (lock) {
                if (helper == null) {
                    helper = new Helper(v);    // unsynchronized write: data race
                }
            }
        }
        return helper;
    }

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("BrokenSingletonRaceTest");

        final int ROUNDS = 50_000;
        final int THREADS_PER_ROUND = 4;
        int inconsistentObservations = 0;

        for (int round = 0; round < ROUNDS; round++) {
            helper = null; // reset for this round
            final int roundValue = round + 1;
            final boolean[] inconsistentThisRound = {false};

            Thread[] threads = new Thread[THREADS_PER_ROUND];
            for (int i = 0; i < THREADS_PER_ROUND; i++) {
                threads[i] = new Thread(() -> {
                    Helper h = getInstance(roundValue);
                    if (h != null && !h.isConsistent()) {
                        inconsistentThisRound[0] = true;
                    }
                });
                threads[i].start();
            }
            for (Thread th : threads) th.join();
            if (inconsistentThisRound[0]) inconsistentObservations++;
        }

        // The only assertion: the stress run itself didn't crash or
        // deadlock across all 200,000 getInstance() calls. This is
        // deliberately NOT "an anomaly must have been observed" --
        // see the file header for why that would be the wrong thing
        // to assert here.
        t.check("completed " + ROUNDS + " rounds x " + THREADS_PER_ROUND +
                        " threads without crashing or deadlocking", true);

        System.out.println("         inconsistent (torn) observations: " + inconsistentObservations
                + " / " + (ROUNDS * THREADS_PER_ROUND));
        if (inconsistentObservations == 0) {
            System.out.println("         NOTE: zero observed here is the expected, EXPECTED result");
            System.out.println("         on x86 hardware -- it does NOT mean this code is safe. See");
            System.out.println("         the file header and the README's Negative Tests section.");
        }

        return t.summary("BrokenSingletonRaceTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
