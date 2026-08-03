/**
 * NEGATIVE TEST -- this file is intentionally buggy.
 *
 * This is the broken twin of VolatilePublicationTest.java: the exact
 * same producer/consumer publish pattern, but with `payload` and
 * `ready` as plain fields instead of `volatile`. That means the
 * producer's write to `ready` and the consumer's read of it are
 * completely unsynchronized -- a real data race under the JMM.
 *
 * Unlike C++, a Java race is never undefined behavior: the JLS
 * guarantees the JVM won't corrupt memory or do something arbitrary.
 * But "not UB" does not mean "harmless" or "guaranteed to terminate."
 * While building this project we confirmed, empirically, on OpenJDK 21
 * HotSpot with default settings, that this exact pattern reliably
 * HANGS: the C2 JIT compiles the consumer's `while (!ready) {}` loop
 * and, seeing no synchronization on `ready`, is entitled to treat it
 * as loop-invariant and hoist the read out entirely -- so the consumer
 * spins forever even though the producer genuinely sets `ready = true`.
 * This is the direct Java analog of the identical bug we found in
 * cpp/tests/unsafe_publication_race.cpp's C++ version.
 *
 * What this test actually does: run several independent rounds, each
 * with a bounded wait, and check that at least one round exhibits the
 * bug (a timeout, or an incorrect observed payload) -- the same
 * "PASS means the bug was caught" inversion as the C++ negative tests.
 *
 * The crucial asymmetry to notice, and the whole point of putting this
 * side by side with the C++ version: this result is EMPIRICAL, not a
 * language guarantee. It reproduced reliably in our testing on this
 * JVM/JIT/hardware combination, but nothing in the JLS promises it
 * will reproduce on every JVM, every version, or with different JIT
 * flags (e.g. `-Xint`, interpreter-only, would very likely never hang,
 * since there's no JIT to do the hoisting). Compare with
 * ThreadSanitizer's report on the C++ side, which is a direct,
 * deterministic instrumentation-based finding, not a "we tried it and
 * it happened" observation. That gap -- deterministic tool-based proof
 * vs. empirical reproduction -- is exactly the asymmetry Section 3 of
 * the README describes.
 */
public class UnsafePublicationRaceTest {

    static int payload;
    static boolean ready; // plain -- NOT volatile

    /** Runs one round: producer publishes payload+ready after a short
     *  delay, consumer spins (unsynchronized) waiting for ready. Returns
     *  true if the bug manifested (timeout, or the consumer observed
     *  ready==true but a stale/wrong payload). */
    private static boolean runRoundAndCheckForBug(int roundValue, long timeoutMillis)
            throws InterruptedException {
        payload = 0;
        ready = false;
        final int expected = roundValue;
        final int[] observed = {Integer.MIN_VALUE};
        final boolean[] sawReady = {false};

        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            payload = expected;
            ready = true;
        });

        Thread consumer = new Thread(() -> {
            while (!ready) {
                // deliberately NOT Thread.onSpinWait()/yield(): a tight,
                // unsynchronized poll loop is exactly the shape that
                // triggers the JIT hoisting we're demonstrating here.
            }
            sawReady[0] = true;
            observed[0] = payload;
        });

        consumer.setDaemon(true); // so a hung consumer can't block JVM exit
        producer.setDaemon(true);
        consumer.start();
        producer.start();

        consumer.join(timeoutMillis);
        producer.join(timeoutMillis);

        if (consumer.isAlive()) {
            return true; // BUG: consumer never observed `ready` become true
        }
        return !sawReady[0] || observed[0] != expected; // BUG: wrong/missing payload
    }

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("UnsafePublicationRaceTest");

        final int ROUNDS = 5;
        final long TIMEOUT_MS = 3000;
        boolean bugReproduced = false;

        for (int round = 0; round < ROUNDS && !bugReproduced; round++) {
            if (runRoundAndCheckForBug(round + 1, TIMEOUT_MS)) {
                bugReproduced = true;
            }
        }

        t.check("the unsynchronized publish pattern's bug was reproduced within "
                        + ROUNDS + " rounds (hang or wrong payload observed)",
                bugReproduced);
        if (!bugReproduced) {
            System.out.println("         NOTE: the bug did not manifest on this JVM/run.");
            System.out.println("         That does NOT mean the code is correct -- see the");
            System.out.println("         file's header comment on why this is empirical,");
            System.out.println("         not a guarantee, unlike ThreadSanitizer's C++ report.");
        }

        return t.summary("UnsafePublicationRaceTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
