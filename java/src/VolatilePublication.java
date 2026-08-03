/**
 * Demonstrates safe publication using `volatile`.
 *
 * The writer publishes `payload` (a plain field) and then flips a
 * `volatile` flag. Because a volatile write is a *release* and a
 * volatile read of the same field is the matching *acquire*, the JMM
 * guarantees that once the reader observes `ready == true`, it also
 * observes the plain write to `payload` that happened before it.
 *
 * Try removing `volatile` and this program can (on some JITs/CPUs,
 * over enough iterations) print 0 instead of 42 -- or spin forever,
 * since the reader may never observe the flag flip at all.
 */
public class VolatilePublication {

    private static volatile boolean ready = false;
    private static int payload;

    private static void produce() {
        payload = 42;   // (1) plain write
        ready = true;   // (2) volatile write -- release
    }

    private static void consume() throws InterruptedException {
        while (!ready) {   // (3) volatile read -- acquire
            Thread.onSpinWait();
        }
        System.out.println("Consumer saw payload = " + payload);
    }

    public static void main(String[] args) throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try {
                consume();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Give the consumer a head start spinning on `ready`.
        Thread.sleep(50);
        produce();

        consumer.join();
    }
}
