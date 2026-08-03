/** Runs every test suite in this directory in one JVM invocation and
 *  aggregates the result -- the Java analog of `ctest` on the C++ side.
 *  Exits 0 if everything passed, 1 if anything failed (suitable for CI). */
public class RunAllTests {

    public static void main(String[] args) throws InterruptedException {
        int failures = 0;

        failures += CasCounterTest.run();
        System.out.println();
        failures += SpscRingBufferTest.run();
        System.out.println();
        failures += DoubleCheckedLockingTest.run();
        System.out.println();
        failures += RwLockCacheTest.run();
        System.out.println();
        failures += IllegalMonitorStateTest.run();
        System.out.println();
        failures += SpinLockTest.run();
        System.out.println();
        failures += StampedLockTest.run();
        System.out.println();
        failures += FencesTest.run();
        System.out.println();
        failures += VolatilePublicationTest.run();
        System.out.println();
        failures += BlockingQueueDemoTest.run();
        System.out.println();
        failures += ClassicBlockingQueueTest.run();
        System.out.println();
        failures += CasMpmcQueueTest.run();
        System.out.println();
        failures += UnsafePublicationRaceTest.run();
        System.out.println();
        failures += BrokenSingletonRaceTest.run();
        System.out.println();

        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failures + " TEST(S) FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
