import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Same suite as cpp/tests/cas_counter_test.cpp -- see that file for the
 *  C++ side of this exact comparison. */
public class CasCounterTest {

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("CasCounterTest");

        // Single-threaded sanity check.
        AtomicInteger single = new AtomicInteger(0);
        for (int i = 0; i < 1000; i++) CasCounter.manualCasIncrement(single);
        t.checkEquals("manual CAS loop reaches 1000 single-threaded", 1000, single.get());

        // Under real contention: no lost updates.
        final int THREADS = 8;
        final int PER_THREAD = 20_000;

        AtomicInteger manual = new AtomicInteger(0);
        runConcurrently(THREADS, () -> {
            for (int j = 0; j < PER_THREAD; j++) CasCounter.manualCasIncrement(manual);
        });
        t.checkEquals("manual CAS loop under contention: no lost updates",
                THREADS * PER_THREAD, manual.get());

        AtomicInteger builtin = new AtomicInteger(0);
        runConcurrently(THREADS, () -> {
            for (int j = 0; j < PER_THREAD; j++) builtin.incrementAndGet();
        });
        t.checkEquals("incrementAndGet under contention: no lost updates",
                THREADS * PER_THREAD, builtin.get());

        LongAdder adder = new LongAdder();
        runConcurrently(THREADS, () -> {
            for (int j = 0; j < PER_THREAD; j++) adder.increment();
        });
        t.checkEquals("LongAdder under contention: no lost updates",
                (long) (THREADS * PER_THREAD), adder.sum());

        return t.summary("CasCounterTest");
    }

    private static void runConcurrently(int threads, Runnable task) throws InterruptedException {
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(task);
            ts[i].start();
        }
        for (Thread th : ts) th.join();
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
