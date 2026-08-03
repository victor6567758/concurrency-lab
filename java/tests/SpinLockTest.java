/** Same suite as cpp/tests/spin_lock_test.cpp -- see that file for the
 *  C++ side of this exact comparison. */
public class SpinLockTest {

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("SpinLockTest");

        SpinLock lock = new SpinLock();

        // Mutual exclusion: an "inside the critical section" counter
        // must never be observed above 1 by another thread that also
        // holds the lock while checking it.
        final int THREADS = 8;
        final int PER_THREAD = 20_000;
        int[] counter = {0};
        int[] insideCount = {0};
        boolean[] mutualExclusionViolated = {false};

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < PER_THREAD; j++) {
                    lock.lock();
                    try {
                        insideCount[0]++;
                        if (insideCount[0] != 1) mutualExclusionViolated[0] = true;
                        counter[0]++; // plain increment -- safe only because of the lock
                        insideCount[0]--;
                    } finally {
                        lock.unlock();
                    }
                }
            });
            threads[i].start();
        }
        for (Thread th : threads) th.join();

        t.check("no lost updates under contention", counter[0] == THREADS * PER_THREAD);
        t.check("mutual exclusion never violated (never >1 thread inside)",
                !mutualExclusionViolated[0]);

        return t.summary("SpinLockTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
