import java.util.concurrent.atomic.AtomicLong;

/**
 * Head-to-head throughput comparison: {@link ClassicBlockingQueue}
 * (one lock, two condition variables -- producers/consumers that find
 * the queue full/empty actually sleep, and get woken by signal()) vs
 * {@link CasMpmcQueue} (no lock at all -- threads that lose a race
 * just spin and retry a CAS). Same total item count, same varying
 * numbers of producer/consumer threads, run back to back so the JIT
 * is equally warmed up for both by the time the numbers that matter
 * are printed.
 *
 * See README section 10 for the results from a representative run and
 * what they say about when CAS actually pays for itself.
 */
public class QueueBenchmark {

    private static final int TOTAL_ITEMS = 2_000_000;
    private static final int QUEUE_CAPACITY = 1 << 10; // 1024, power of two for CasMpmcQueue

    public static void main(String[] args) throws InterruptedException {
        int[][] configs = {
            {1, 1},   // no contention: baseline
            {2, 2},
            {4, 4},
            {8, 8},
        };

        System.out.printf("%-10s %-10s %-24s %-24s%n",
                "producers", "consumers", "classic (lock+condvar)", "CAS (lock-free)");
        for (int[] cfg : configs) {
            long classicMs = runClassic(cfg[0], cfg[1]);
            long casMs = runCas(cfg[0], cfg[1]);
            System.out.printf("%-10d %-10d %-24s %-24s%n",
                    cfg[0], cfg[1],
                    classicMs + " ms (" + (TOTAL_ITEMS / Math.max(1, classicMs)) + " items/ms)",
                    casMs + " ms (" + (TOTAL_ITEMS / Math.max(1, casMs)) + " items/ms)");
        }
    }

    private static long runClassic(int producers, int consumers) throws InterruptedException {
        ClassicBlockingQueue<Integer> q = new ClassicBlockingQueue<>(QUEUE_CAPACITY);
        AtomicLong produced = new AtomicLong(0);
        final Integer POISON = Integer.MIN_VALUE;

        Thread[] prod = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            prod[p] = new Thread(() -> {
                long n;
                while ((n = produced.getAndIncrement()) < TOTAL_ITEMS) {
                    try {
                        q.put((int) n);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }
        // Consumers stop on a poison pill, not a shared counter --
        // checking a counter and then calling a *blocking* take() has
        // a race: a consumer can see "not done yet", then find the
        // queue empty and every remaining item already claimed by a
        // sibling consumer, and block forever. One pill per consumer,
        // pushed only after all producers have finished, sidesteps
        // that entirely.
        Thread[] cons = new Thread[consumers];
        for (int c = 0; c < consumers; c++) {
            cons[c] = new Thread(() -> {
                try {
                    while (true) {
                        int item = q.take();
                        if (item == POISON) return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        long start = System.nanoTime();
        for (Thread t : prod) t.start();
        for (Thread t : cons) t.start();
        for (Thread t : prod) t.join();
        for (int c = 0; c < consumers; c++) q.put(POISON);
        for (Thread t : cons) t.join();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static long runCas(int producers, int consumers) throws InterruptedException {
        CasMpmcQueue<Integer> q = new CasMpmcQueue<>(QUEUE_CAPACITY);
        AtomicLong produced = new AtomicLong(0);
        AtomicLong consumedCount = new AtomicLong(0);

        Thread[] prod = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            prod[p] = new Thread(() -> {
                long n;
                while ((n = produced.getAndIncrement()) < TOTAL_ITEMS) {
                    while (!q.offer((int) n)) {
                        Thread.onSpinWait();
                    }
                }
            });
        }
        Thread[] cons = new Thread[consumers];
        for (int c = 0; c < consumers; c++) {
            cons[c] = new Thread(() -> {
                while (consumedCount.get() < TOTAL_ITEMS) {
                    if (q.poll() == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    consumedCount.incrementAndGet();
                }
            });
        }

        long start = System.nanoTime();
        for (Thread t : prod) t.start();
        for (Thread t : cons) t.start();
        for (Thread t : prod) t.join();
        for (Thread t : cons) t.join();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
