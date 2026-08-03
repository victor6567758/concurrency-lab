import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Same shape of suite as BlockingQueueDemoTest, but exercising the
 *  hand-rolled lock+condition queue instead of ArrayBlockingQueue, plus
 *  an MPMC stress test (multiple producers AND consumers) since that's
 *  the property BlockingQueueDemoTest never had to check for a
 *  single-producer/single-consumer demo. */
public class ClassicBlockingQueueTest {

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("ClassicBlockingQueueTest");

        // FIFO order, single-threaded.
        ClassicBlockingQueue<Integer> q = new ClassicBlockingQueue<>(10);
        q.put(1);
        q.put(2);
        q.put(3);
        t.checkEquals("take returns items in FIFO order (1)", 1, q.take());
        t.checkEquals("take returns items in FIFO order (2)", 2, q.take());
        t.checkEquals("take returns items in FIFO order (3)", 3, q.take());

        // take() actually blocks when empty, wakes on put().
        ClassicBlockingQueue<Integer> q2 = new ClassicBlockingQueue<>(10);
        AtomicBoolean consumerReturned = new AtomicBoolean(false);
        int[] received = {-1};

        Thread consumer = new Thread(() -> {
            try {
                received[0] = q2.take();
                consumerReturned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        Thread.sleep(100);
        t.check("take() is still blocking on an empty queue after 100ms",
                !consumerReturned.get());
        q2.put(42);
        consumer.join();
        t.check("take() unblocks once an item is pushed", consumerReturned.get());
        t.checkEquals("take() returns the pushed item", 42, received[0]);

        // put() actually blocks when full, wakes on take().
        ClassicBlockingQueue<Integer> q3 = new ClassicBlockingQueue<>(1);
        q3.put(1);
        AtomicBoolean producerReturned = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            try {
                q3.put(2);
                producerReturned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        Thread.sleep(100);
        t.check("put() is still blocking on a full queue after 100ms",
                !producerReturned.get());
        t.checkEquals("the item already in the queue is unaffected", 1, q3.take());
        producer.join();
        t.check("put() unblocks once a slot frees up", producerReturned.get());
        t.checkEquals("the queue now holds the item that was blocked on put()",
                2, q3.take());

        // MPMC stress: 4 producers, 4 consumers, no lost/duplicated items.
        // Termination uses one poison pill per consumer, pushed only
        // after every producer has finished -- NOT a shared "consumed
        // so far" counter. A counter-based stopping condition on a
        // *blocking* take() is a deadlock waiting to happen: a consumer
        // can pass the "count < TOTAL" check, then block in take()
        // forever if every remaining item was already claimed by
        // another consumer between the check and the call.
        final int PRODUCERS = 4, CONSUMERS = 4, PER_PRODUCER = 20_000;
        final Integer POISON = Integer.MIN_VALUE;
        ClassicBlockingQueue<Integer> q4 = new ClassicBlockingQueue<>(64);
        AtomicLong checksum = new AtomicLong(0);
        AtomicLong consumedCount = new AtomicLong(0);
        long expectedSum = 0;
        for (int p = 0; p < PRODUCERS; p++) {
            for (int i = 0; i < PER_PRODUCER; i++) expectedSum += i;
        }

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            producers[p] = new Thread(() -> {
                try {
                    for (int i = 0; i < PER_PRODUCER; i++) q4.put(i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread[] consumers = new Thread[CONSUMERS];
        for (int c = 0; c < CONSUMERS; c++) {
            consumers[c] = new Thread(() -> {
                long local = 0;
                long localCount = 0;
                try {
                    while (true) {
                        int item = q4.take();
                        if (item == POISON) break;
                        local += item;
                        localCount++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                checksum.addAndGet(local);
                consumedCount.addAndGet(localCount);
            });
        }
        for (Thread th : producers) th.start();
        for (Thread th : consumers) th.start();
        for (Thread th : producers) th.join();
        for (int c = 0; c < CONSUMERS; c++) q4.put(POISON); // one pill per consumer
        for (Thread th : consumers) th.join();

        t.checkEquals("MPMC stress: every item consumed exactly once (count)",
                (long) (PRODUCERS * PER_PRODUCER), consumedCount.get());
        t.checkEquals("MPMC stress: checksum matches (no lost/duplicated items)",
                expectedSum, checksum.get());

        return t.summary("ClassicBlockingQueueTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
