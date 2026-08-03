/** Same suite as cpp/tests/spsc_ring_buffer_test.cpp -- see that file for
 *  the C++ side of this exact comparison. */
public class SpscRingBufferTest {

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("SpscRingBufferTest");

        // Functional: fills exactly to capacity, then rejects; FIFO order preserved.
        SpscRingBuffer<Integer> rb = new SpscRingBuffer<>(4);
        t.check("offer #1 succeeds", rb.offer(10));
        t.check("offer #2 succeeds", rb.offer(20));
        t.check("offer #3 succeeds", rb.offer(30));
        t.check("offer #4 succeeds (buffer now full at capacity)", rb.offer(40));
        t.check("offer #5 fails while full", !rb.offer(50));

        t.checkEquals("poll #1 returns items in FIFO order", 10, rb.poll());
        t.checkEquals("poll #2 returns items in FIFO order", 20, rb.poll());

        // Freed two slots: offers should succeed again (wraparound).
        t.check("offer after poll succeeds (wraparound)", rb.offer(50));
        t.check("second offer after poll succeeds (wraparound)", rb.offer(60));

        t.checkEquals("poll #3 returns items in FIFO order", 30, rb.poll());
        t.checkEquals("poll #4 returns items in FIFO order", 40, rb.poll());
        t.checkEquals("poll #5 returns the first wrapped-around item", 50, rb.poll());
        t.checkEquals("poll #6 returns the second wrapped-around item", 60, rb.poll());
        t.checkEquals("poll returns null once empty", null, rb.poll());

        // Concurrent: one producer, one consumer, verify no lost/duplicated
        // items via a checksum -- the property the volatile-backed
        // head/tail publish/subscribe pattern is there to guarantee.
        final int total = 200_000;
        SpscRingBuffer<Integer> concurrentRb = new SpscRingBuffer<>(1024);
        long[] sum = {0};

        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                while (!concurrentRb.offer(i)) Thread.onSpinWait();
            }
        });
        Thread consumer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                Integer item;
                while ((item = concurrentRb.poll()) == null) Thread.onSpinWait();
                sum[0] += item;
            }
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        long expected = (long) total * (total - 1) / 2;
        t.checkEquals("concurrent producer/consumer: checksum matches (no lost/dup items)",
                expected, sum[0]);

        return t.summary("SpscRingBufferTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
