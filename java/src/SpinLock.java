import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal test-and-test-and-set (TTAS) spinlock, built on
 * AtomicBoolean. Compare with cpp/include/spin_lock.hpp, which does
 * the identical dance on std::atomic_flag.
 *
 * "Test-and-TEST-and-set" (as opposed to plain test-and-set) matters
 * for performance under contention: spinning on a plain read of the
 * flag first, rather than repeatedly attempting the CAS itself, avoids
 * hammering cache coherence. A CAS is a read-modify-write that
 * invalidates the cache line on every other core holding it -- even
 * when it fails -- while a plain read lets the line stay shared and
 * cheap to poll until it actually looks free.
 *
 * When to reach for this instead of ReentrantLock/synchronized: only
 * for extremely short critical sections where the lock is expected to
 * be held for a handful of instructions, on a machine with at least as
 * many cores as contending threads. Spin for longer than that, or with
 * more threads than cores, and you're just burning CPU that a blocking
 * lock would have handed to someone useful.
 */
public class SpinLock {

    private final AtomicBoolean locked = new AtomicBoolean(false);

    public void lock() {
        while (true) {
            // Test: spin on a cheap read until it looks free.
            while (locked.get()) {
                Thread.onSpinWait(); // JEP 285 hint: "I'm busy-waiting, optimize for that"
            }
            // ...and-set: only now attempt the actual CAS.
            if (locked.compareAndSet(false, true)) {
                return; // we got it
            }
            // someone beat us to it between the read and the CAS; retry
        }
    }

    public void unlock() {
        locked.set(false); // release
    }

    public static void main(String[] args) throws InterruptedException {
        SpinLock lock = new SpinLock();
        int[] counter = {0};
        final int THREADS = 8;
        final int PER_THREAD = 100_000;

        Thread[] threads = new Thread[THREADS];
        long start = System.nanoTime();
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < PER_THREAD; j++) {
                    lock.lock();
                    try {
                        counter[0]++; // plain, unsynchronized increment -- safe only because of the lock
                    } finally {
                        lock.unlock();
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("counter = " + counter[0] + " (expected " + (THREADS * PER_THREAD) + ")");
        System.out.println("elapsed = " + elapsedMs + " ms");
    }
}
