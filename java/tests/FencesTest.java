import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Repeats FencesDemo's publish/subscribe pattern many times and checks
 * the consumer always observes the fully-published values. This is a
 * functional smoke test, not a race detector -- correct code always
 * passes it, but passing it doesn't itself prove the release/acquire
 * fence pairing was necessary (only running under a memory-model
 * checker like jcstress could tell you that). What it does verify: the
 * VarHandle fence API behaves as documented and the pattern works
 * end-to-end across many repeated runs, each in fresh state.
 */
public class FencesTest {

    static int payload;
    static int ready;

    static final VarHandle READY;
    static {
        try {
            READY = MethodHandles.lookup()
                    .findStaticVarHandle(FencesTest.class, "ready", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("FencesTest");

        final int TRIALS = 50;
        boolean allCorrect = true;

        for (int trial = 0; trial < TRIALS; trial++) {
            payload = 0;
            READY.setOpaque(0);
            final int expected = trial + 1;

            Thread producer = new Thread(() -> {
                payload = expected;
                VarHandle.releaseFence();
                READY.setOpaque(1);
            });
            final int[] observed = {-1};
            Thread consumer = new Thread(() -> {
                while ((int) READY.getOpaque() == 0) Thread.onSpinWait();
                VarHandle.acquireFence();
                observed[0] = payload;
            });

            consumer.start();
            producer.start();
            producer.join();
            consumer.join();

            if (observed[0] != expected) allCorrect = false;
        }

        t.check("consumer observed the fully-published payload on all " + TRIALS + " trials",
                allCorrect);

        return t.summary("FencesTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
