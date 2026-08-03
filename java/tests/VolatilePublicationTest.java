/** Same suite as cpp/tests/volatile_publication_test.cpp -- see that
 *  file for the C++ side of this exact comparison. Repeats
 *  VolatilePublication.java's exact producer/consumer pattern (payload
 *  published as a plain field, observed via a volatile flag) across
 *  many fresh trials, and checks the consumer always sees the
 *  fully-published value.
 *
 *  As with FencesTest, this is a functional smoke test, not a formal
 *  race-absence proof -- correct code always passes it, but passing it
 *  alone doesn't prove `volatile` was load-bearing. Java has no
 *  ThreadSanitizer-equivalent to prove that the way
 *  cpp/negative_tests/unsafe_publication_race.cpp does for C++ (see
 *  the README's "Negative tests" section for why). */
public class VolatilePublicationTest {

    static volatile boolean ready;
    static int payload;

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("VolatilePublicationTest");

        final int TRIALS = 200;
        boolean allCorrect = true;

        for (int trial = 0; trial < TRIALS; trial++) {
            payload = 0;
            ready = false;
            final int expected = trial + 1;
            final int[] observed = {-1};

            Thread consumer = new Thread(() -> {
                while (!ready) {
                    Thread.onSpinWait();
                }
                observed[0] = payload;
            });
            Thread producer = new Thread(() -> {
                payload = expected; // (1) plain write
                ready = true;        // (2) volatile write -- release
            });

            consumer.start();
            producer.start();
            producer.join();
            consumer.join();

            if (observed[0] != expected) allCorrect = false;
        }

        t.check("consumer observed the fully-published payload on all " + TRIALS + " trials",
                allCorrect);

        return t.summary("VolatilePublicationTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
