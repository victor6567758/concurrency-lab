import java.util.concurrent.atomic.AtomicLong;

/** Mirrors CasCounterTest's "no lost updates under contention" property,
 *  but for a queue: no lost, no duplicated, no reordered-within-slot
 *  items even with multiple producers AND multiple consumers hammering
 *  the same ring buffer via CAS. Also checks basic FIFO/full/empty
 *  behavior single-threaded first, since a queue that fails those
 *  wouldn't be worth stress-testing at all. */
public class CasMpmcQueueTest {

    static int run() {
        TestHarness t = new TestHarness();
        System.out.println("CasMpmcQueueTest");

        // Single-threaded: FIFO order.
        CasMpmcQueue<Integer> q = new CasMpmcQueue<>(4);
        t.check("offer succeeds while not full (1)", q.offer(1));
        t.check("offer succeeds while not full (2)", q.offer(2));
        t.checkEquals("poll returns items in FIFO order (1)", 1, q.poll());
        t.checkEquals("poll returns items in FIFO order (2)", 2, q.poll());

        // Full/empty detection.
        CasMpmcQueue<Integer> q2 = new CasMpmcQueue<>(2);
        t.check("offer succeeds (fills capacity)", q2.offer(10));
        t.check("offer succeeds (fills capacity)", q2.offer(20));
        t.check("offer fails once full", !q2.offer(30));
        t.checkEquals("empty queue returns null from poll", 10, q2.poll());
        t.checkEquals("poll after freeing a slot", 20, q2.poll());
        t.check("poll on empty queue returns null", q2.poll() == null);

        // Constructor rejects non-power-of-two capacity.
        boolean threw = false;
        try {
            new CasMpmcQueue<Integer>(3);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        t.check("constructor rejects non-power-of-two capacity", threw);

        // MPMC stress: 4 producers, 4 consumers, no lost/duplicated
        // items -- the CAS analog of what ClassicBlockingQueueTest
        // checks for the lock-based queue, and the same property
        // CasCounterTest checks for a bare counter, just applied to
        // every slot in a ring buffer instead of one shared integer.
        final int PRODUCERS = 4, CONSUMERS = 4, PER_PRODUCER = 20_000;
        final int TOTAL = PRODUCERS * PER_PRODUCER;
        CasMpmcQueue<Integer> q3 = new CasMpmcQueue<>(64);
        AtomicLong checksum = new AtomicLong(0);
        AtomicLong consumedCount = new AtomicLong(0);
        long expectedSum = 0;
        for (int p = 0; p < PRODUCERS; p++) {
            for (int i = 0; i < PER_PRODUCER; i++) expectedSum += i;
        }

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            producers[p] = new Thread(() -> {
                for (int i = 0; i < PER_PRODUCER; i++) {
                    while (!q3.offer(i)) Thread.onSpinWait();
                }
            });
        }
        // Non-blocking poll() makes a counter-based stopping condition
        // safe here (unlike the blocking take() case): a consumer that
        // finds nothing to poll just spins and re-checks, it never
        // sleeps waiting for a wakeup that might not come.
        Thread[] consumers = new Thread[CONSUMERS];
        for (int c = 0; c < CONSUMERS; c++) {
            consumers[c] = new Thread(() -> {
                long local = 0;
                while (consumedCount.get() < TOTAL) {
                    Integer item = q3.poll();
                    if (item == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    local += item;
                    consumedCount.incrementAndGet();
                }
                checksum.addAndGet(local);
            });
        }
        for (Thread th : producers) th.start();
        for (Thread th : consumers) th.start();
        try {
            for (Thread th : producers) th.join();
            for (Thread th : consumers) th.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        t.checkEquals("MPMC stress: every item consumed exactly once (count)",
                (long) TOTAL, consumedCount.get());
        t.checkEquals("MPMC stress: checksum matches (no lost/duplicated items)",
                expectedSum, checksum.get());

        return t.summary("CasMpmcQueueTest");
    }

    public static void main(String[] args) {
        System.exit(run());
    }
}
