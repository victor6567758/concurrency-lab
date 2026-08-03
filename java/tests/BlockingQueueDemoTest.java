import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same suite as cpp/tests/blocking_queue_test.cpp -- see that file for
 *  the C++ side of this exact comparison. BlockingQueueDemo.java uses
 *  java.util.concurrent.ArrayBlockingQueue directly rather than
 *  defining its own reusable class (unlike the C++ side, where
 *  BlockingQueue had to be hand-rolled and was extracted into
 *  include/blocking_queue.hpp for testing) -- so this test exercises
 *  ArrayBlockingQueue itself, the same way BlockingQueueDemo does. */
public class BlockingQueueDemoTest {

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("BlockingQueueDemoTest");

        // FIFO order, single-threaded.
        BlockingQueue<Integer> q = new ArrayBlockingQueue<>(10);
        q.put(1);
        q.put(2);
        q.put(3);
        t.checkEquals("take returns items in FIFO order (1)", 1, q.take());
        t.checkEquals("take returns items in FIFO order (2)", 2, q.take());
        t.checkEquals("take returns items in FIFO order (3)", 3, q.take());

        // take() actually blocks when empty, and wakes up once an item
        // arrives -- the entire reason to reach for a BlockingQueue
        // instead of a plain Queue with a null-returning poll().
        BlockingQueue<Integer> q2 = new ArrayBlockingQueue<>(10);
        AtomicBoolean consumerReturned = new AtomicBoolean(false);
        int[] received = {-1};

        Thread consumer = new Thread(() -> {
            try {
                received[0] = q2.take(); // should block here until we put
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

        // put() actually blocks when full, and wakes up once a slot
        // frees -- the other half of the same guarantee.
        BlockingQueue<Integer> q3 = new ArrayBlockingQueue<>(1);
        q3.put(1); // fill it
        AtomicBoolean producerReturned = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            try {
                q3.put(2); // should block here until we take
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

        return t.summary("BlockingQueueDemoTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
