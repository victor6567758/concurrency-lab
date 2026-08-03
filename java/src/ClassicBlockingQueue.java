import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The classic textbook bounded producer/consumer queue: one lock, two
 * condition variables (one to wake waiting producers, one to wake
 * waiting consumers). This is what {@code ArrayBlockingQueue} does
 * *internally* -- see the JDK source, it's built exactly this way --
 * but {@code BlockingQueueDemo.java} never shows that mechanism because
 * it just calls {@code put()}/{@code take()} on the library class.
 * This file hand-rolls it so the locking/waiting is visible, and so it
 * can be benchmarked head-to-head against {@link CasMpmcQueue} in
 * {@link QueueBenchmark} -- see README section 10 for what that
 * comparison shows.
 *
 * Safe for any number of producer and consumer threads (MPMC), unlike
 * {@link CasMpmcQueue}'s SPSC cousin {@code SpscRingBuffer}.
 */
public class ClassicBlockingQueue<T> {

    private final Object[] buffer;
    private final int capacity;
    private int head = 0;   // next slot to read
    private int count = 0;  // number of items currently held
    private int tail = 0;   // next slot to write

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public ClassicBlockingQueue(int capacity) {
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    /** Blocks while the queue is full. */
    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (count == capacity) {
                notFull.await(); // atomically releases the lock and sleeps
            }
            buffer[tail] = item;
            tail = (tail + 1) % capacity;
            count++;
            notEmpty.signal(); // wake exactly one waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /** Blocks while the queue is empty. */
    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                notEmpty.await();
            }
            T item = (T) buffer[head];
            buffer[head] = null;
            head = (head + 1) % capacity;
            count--;
            notFull.signal(); // wake exactly one waiting producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        final int ITEMS = 10;
        ClassicBlockingQueue<Integer> queue = new ClassicBlockingQueue<>(4);
        final Integer POISON_PILL = Integer.MIN_VALUE;

        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= ITEMS; i++) {
                    queue.put(i);
                    System.out.println("produced " + i);
                }
                queue.put(POISON_PILL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "producer");

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    int item = queue.take();
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
