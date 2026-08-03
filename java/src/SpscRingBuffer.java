import java.util.concurrent.atomic.AtomicLong;

/**
 * A single-producer/single-consumer lock-free ring buffer, in the style
 * of the LMAX Disruptor. Only correct with exactly one producer thread
 * and one consumer thread -- for multiple producers or consumers you'd
 * need CAS on the index itself (see java.util.concurrent.ConcurrentLinkedQueue
 * for the general MPMC case).
 *
 * Why it's safe without locks:
 *  - `tail` is only ever written by the producer, `head` only by the
 *    consumer -- so there is never a write/write race on either index.
 *  - The producer writes the slot, THEN publishes by advancing `tail`.
 *    The consumer reads `tail` (to check for data), THEN reads the slot.
 *    Because `tail` uses release-on-write / (volatile) acquire-on-read
 *    semantics, the slot write always happens-before the consumer's read
 *    of that slot -- the same publish/subscribe pattern as
 *    VolatilePublication.java, just applied per-slot.
 *  - `lazySet` is Java's release-only store: cheaper than a full volatile
 *    write (no StoreLoad fence) because we don't need the *producer* to
 *    see a fresh value immediately -- only the consumer, on its own next
 *    read, needs to see it eventually with the right ordering.
 */
public class SpscRingBuffer<T> {

    private final Object[] buffer;
    private final int mask;
    // AtomicLong gives us a volatile-like field plus the ability to
    // use the cheaper lazySet (ordered store) instead of a full
    // sequentially-consistent write.
    private final AtomicLong head = new AtomicLong(0); // next slot consumer will read
    private final AtomicLong tail = new AtomicLong(0); // next slot producer will write

    public SpscRingBuffer(int capacityPowerOfTwo) {
        if (Integer.bitCount(capacityPowerOfTwo) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        this.buffer = new Object[capacityPowerOfTwo];
        this.mask = capacityPowerOfTwo - 1;
    }

    /** Producer-only. Returns false if the buffer is full. */
    public boolean offer(T item) {
        long currentTail = tail.get();
        long wrapPoint = currentTail - buffer.length;
        if (head.get() <= wrapPoint) {
            return false; // full: consumer hasn't caught up yet
        }
        buffer[(int) (currentTail & mask)] = item;
        tail.lazySet(currentTail + 1); // publish: release, not full fence
        return true;
    }

    /** Consumer-only. Returns null if the buffer is empty. */
    @SuppressWarnings("unchecked")
    public T poll() {
        long currentHead = head.get();
        if (currentHead >= tail.get()) {
            return null; // empty: producer hasn't published anything new
        }
        int idx = (int) (currentHead & mask);
        T item = (T) buffer[idx];
        buffer[idx] = null; // avoid holding a stale reference (GC nepotism)
        head.lazySet(currentHead + 1);
        return item;
    }

    public static void main(String[] args) throws InterruptedException {
        final int total = 5_000_000;
        SpscRingBuffer<Integer> rb = new SpscRingBuffer<>(1 << 12); // 4096 slots

        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                while (!rb.offer(i)) {
                    Thread.onSpinWait(); // buffer full, back off briefly
                }
            }
        }, "producer");

        Thread consumer = new Thread(() -> {
            long sum = 0;
            for (int i = 0; i < total; i++) {
                Integer item;
                while ((item = rb.poll()) == null) {
                    Thread.onSpinWait(); // buffer empty, back off briefly
                }
                sum += item;
            }
            System.out.println("Consumed " + total + " items, checksum = " + sum);
        }, "consumer");

        long start = System.nanoTime();
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("Elapsed: " + elapsedMs + " ms ("
                + (total / Math.max(1, elapsedMs)) + " items/ms)");
    }
}
