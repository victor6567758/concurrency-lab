import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Compares three ways to count concurrently in Java:
 *  1) A manual CAS loop over AtomicInteger (shows the mechanics).
 *  2) AtomicInteger.incrementAndGet (same thing, built in).
 *  3) LongAdder, which stripes the counter across cache lines to
 *     reduce CAS contention under heavy parallelism.
 *
 * All three give the same *correctness* guarantee (java.util.concurrent
 * atomics always use a seq-cst-like ordering -- there's no relaxed dial),
 * but LongAdder trades a little read-side cost for far better write
 * throughput when many threads increment at once.
 */
public class CasCounter {

    private static final int THREADS = 8;
    private static final int INCREMENTS_PER_THREAD = 200_000;

    // (1) Manual CAS loop -- illustrates what incrementAndGet does internally.
    static int manualCasIncrement(AtomicInteger counter) {
        int prev, next;
        do {
            prev = counter.get();
            next = prev + 1;
        } while (!counter.compareAndSet(prev, next));
        return next;
    }

    public static void main(String[] args) throws InterruptedException {
        AtomicInteger manual = new AtomicInteger(0);
        AtomicInteger builtin = new AtomicInteger(0);
        LongAdder adder = new LongAdder();

        Runnable manualTask = () -> {
            for (int i = 0; i < INCREMENTS_PER_THREAD; i++) manualCasIncrement(manual);
        };
        Runnable builtinTask = () -> {
            for (int i = 0; i < INCREMENTS_PER_THREAD; i++) builtin.incrementAndGet();
        };
        Runnable adderTask = () -> {
            for (int i = 0; i < INCREMENTS_PER_THREAD; i++) adder.increment();
        };

        runAndTime("manual CAS loop", manualTask);
        runAndTime("AtomicInteger.incrementAndGet", builtinTask);
        runAndTime("LongAdder.increment", adderTask);

        System.out.println("manual  = " + manual.get());
        System.out.println("builtin = " + builtin.get());
        System.out.println("adder   = " + adder.sum());
    }

    private static void runAndTime(String label, Runnable task) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        long start = System.nanoTime();
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(task);
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("%-32s took %d ms%n", label, elapsedMs);
    }
}
