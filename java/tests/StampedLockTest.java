import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Same suite as cpp/tests/seqlock_test.cpp -- see that file for the
 *  C++ side of this exact comparison. Both verify the same property:
 *  a concurrent reader using the optimistic/lock-free path never
 *  observes a torn write, even though the writer updates the guarded
 *  fields in two separate, non-atomic steps. */
public class StampedLockTest {

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("StampedLockTest");

        StampedLockDemo.Point p = new StampedLockDemo.Point();
        final int MOVES = 200_000;
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean invariantViolated = new AtomicBoolean(false);
        AtomicLong readsPerformed = new AtomicLong(0);

        Thread writer = new Thread(() -> {
            for (int i = 0; i < MOVES; i++) p.move(1);
            done.set(true);
        });
        Thread reader = new Thread(() -> {
            while (!done.get()) {
                double[] xy = p.readXY();
                if (xy[0] != xy[1]) invariantViolated.set(true);
                readsPerformed.incrementAndGet();
            }
        });

        writer.start();
        reader.start();
        writer.join();
        reader.join();

        t.check("reader performed at least one optimistic read", readsPerformed.get() > 0);
        t.check("optimistic reads never observed a torn write (x == y always held)",
                !invariantViolated.get());

        double[] finalXY = p.readXY();
        t.checkEquals("final x == final y", finalXY[0], finalXY[1]);
        t.checkEquals("final value matches number of moves", (double) MOVES, finalXY[0]);

        return t.summary("StampedLockTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
