import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Classic producer/consumer using a bounded blocking queue.
 *  put() blocks when full, take() blocks when empty -- no manual
 *  wait/notify or locking required. Compare with cpp/blocking_queue_demo.cpp,
 *  where this has to be built by hand from a mutex + condition_variable. */
public class BlockingQueueDemo {

    private static final int CAPACITY = 4;
    private static final int ITEMS = 10;
    private static final Object POISON_PILL = new Object();

    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(CAPACITY);

        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= ITEMS; i++) {
                    queue.put(i);
                    System.out.println("produced " + i);
                }
                queue.put(POISON_PILL); // signal completion
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "producer");

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    Object item = queue.take();
                    if (item == POISON_PILL) break;
                    System.out.println("                 consumed " + item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
    }
}
