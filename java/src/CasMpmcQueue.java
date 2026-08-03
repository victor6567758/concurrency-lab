import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A bounded, lock-free, multi-producer/multi-consumer queue -- unlike
 * {@link SpscRingBuffer} (exactly one producer, one consumer, no CAS
 * needed) or {@link ClassicBlockingQueue} (any number of producers and
 * consumers, but needs a lock), this handles multiple producers AND
 * multiple consumers WITHOUT ever blocking a thread.
 *
 * This is Dmitry Vyukov's bounded MPMC ring buffer algorithm. The key
 * idea: every slot carries its own {@code sequence} number alongside
 * its data, and a thread claims a slot with a CAS on the shared
 * head/tail index -- if another thread got there first, the CAS fails
 * and the loop just re-reads the index and tries again (no lock, no
 * blocking, just a retry).
 *
 * Why CAS is unavoidable here (and wasn't in SpscRingBuffer): with a
 * single producer, only one thread ever writes `tail`, so a plain
 * release-store is enough -- there's no write/write race to resolve.
 * With MULTIPLE producers, two threads can race to claim the same
 * slot at once; something has to atomically decide which one wins.
 * That "atomically decide" step is exactly what compareAndSet does:
 * "if the index is still what I last saw, advance it to N+1" -- and
 * if it isn't (because another producer already won), the CAS simply
 * reports failure instead of corrupting the index, and the loser
 * retries against the new value.
 *
 * See README section 10 for a head-to-head benchmark against
 * {@link ClassicBlockingQueue} and what it shows about when CAS is
 * worth it.
 */
public class CasMpmcQueue<T> {

    private final Object[] buffer;
    private final AtomicLongArray sequence; // per-slot state, see below
    private final int mask;

    private final AtomicLong enqueuePos = new AtomicLong(0);
    private final AtomicLong dequeuePos = new AtomicLong(0);

    public CasMpmcQueue(int capacityPowerOfTwo) {
        if (Integer.bitCount(capacityPowerOfTwo) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        this.buffer = new Object[capacityPowerOfTwo];
        this.mask = capacityPowerOfTwo - 1;
        this.sequence = new AtomicLongArray(capacityPowerOfTwo);
        // Slot i starts "ready to be written by producer pos == i".
        for (int i = 0; i < capacityPowerOfTwo; i++) {
            sequence.set(i, i);
        }
    }

    /** Non-blocking. Returns false immediately if the queue is full. */
    public boolean offer(T item) {
        long pos = enqueuePos.get();
        for (;;) {
            int idx = (int) (pos & mask);
            long seq = sequence.get(idx);
            long diff = seq - pos;
            if (diff == 0) {
                // Slot is free for this lap. Try to claim it: if some
                // other producer's CAS already advanced enqueuePos
                // since we read `pos`, this fails harmlessly and we
                // just retry against the new position -- no lock,
                // no blocked thread, just another attempt.
                if (enqueuePos.compareAndSet(pos, pos + 1)) {
                    break;
                }
                // CAS failed: someone else claimed it. Re-read and retry.
                pos = enqueuePos.get();
            } else if (diff < 0) {
                return false; // full: consumers haven't freed a slot yet
            } else {
                pos = enqueuePos.get(); // another producer already moved ahead
            }
        }
        int idx = (int) (pos & mask);
        buffer[idx] = item;
        sequence.set(idx, pos + 1); // publish: makes the slot visible to consumers
        return true;
    }

    /** Non-blocking. Returns null immediately if the queue is empty. */
    @SuppressWarnings("unchecked")
    public T poll() {
        long pos = dequeuePos.get();
        for (;;) {
            int idx = (int) (pos & mask);
            long seq = sequence.get(idx);
            long diff = seq - (pos + 1);
            if (diff == 0) {
                if (dequeuePos.compareAndSet(pos, pos + 1)) {
                    break;
                }
                pos = dequeuePos.get();
            } else if (diff < 0) {
                return null; // empty: no producer has published this slot yet
            } else {
                pos = dequeuePos.get();
            }
        }
        int idx = (int) (pos & mask);
        T item = (T) buffer[idx];
        buffer[idx] = null;
        // Mark the slot ready for the NEXT lap around the ring, not
        // this one -- adding the full capacity (mask + 1) is what
        // makes it match a future producer's `seq - pos == 0` check.
        sequence.set(idx, pos + mask + 1);
        return item;
    }

    public static void main(String[] args) throws InterruptedException {
        final int total = 5_000_000;
        final int producers = 4;
        final int consumers = 4;
        CasMpmcQueue<Integer> q = new CasMpmcQueue<>(1 << 12);

        AtomicLong produced = new AtomicLong(0);
        AtomicLong consumedCount = new AtomicLong(0);
        AtomicLong checksum = new AtomicLong(0);

        Thread[] prod = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            prod[p] = new Thread(() -> {
                long n;
                while ((n = produced.getAndIncrement()) < total) {
                    while (!q.offer((int) n)) {
                        Thread.onSpinWait();
                    }
                }
            }, "producer-" + p);
        }

        Thread[] cons = new Thread[consumers];
        for (int c = 0; c < consumers; c++) {
            cons[c] = new Thread(() -> {
                long localSum = 0;
                while (consumedCount.get() < total) {
                    Integer item = q.poll();
                    if (item == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    localSum += item;
                    consumedCount.incrementAndGet();
                }
                checksum.addAndGet(localSum);
            }, "consumer-" + c);
        }

        long start = System.nanoTime();
        for (Thread t : prod) t.start();
        for (Thread t : cons) t.start();
        for (Thread t : prod) t.join();
        for (Thread t : cons) t.join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("Consumed " + total + " items across " + producers
                + " producers / " + consumers + " consumers, checksum = " + checksum.get());
        System.out.println("Elapsed: " + elapsedMs + " ms ("
                + (total / Math.max(1, elapsedMs)) + " items/ms)");
    }
}
