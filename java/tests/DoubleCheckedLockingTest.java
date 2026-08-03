/** Same suite as cpp/tests/dcl_singletons_test.cpp -- see that file for
 *  the C++ side of this exact comparison. */
public class DoubleCheckedLockingTest {

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("DoubleCheckedLockingTest");

        // FixedSingleton -- every thread's first call must observe the
        // exact same instance, which is exactly what declaring `helper`
        // volatile is there to guarantee.
        checkSingletonIdentity(t, "FixedSingleton",
                DoubleCheckedLocking.FixedSingleton::getInstance);

        // HolderSingleton -- same identity guarantee, provided by the
        // JLS's classloading happens-before edge instead of volatile.
        checkSingletonIdentity(t, "HolderSingleton",
                DoubleCheckedLocking.HolderSingleton::getInstance);

        // Note: BrokenSingleton is deliberately not exercised under a
        // real race here. The JMM permits (doesn't guarantee) the bad
        // reordering that makes it unsafe, so a passing test wouldn't
        // prove it's correct -- it would just prove we got lucky on
        // this JIT/CPU/run, which is the whole point of why the bug is
        // dangerous in the first place.

        return t.summary("DoubleCheckedLockingTest");
    }

    private interface InstanceSupplier {
        DoubleCheckedLocking.Helper get();
    }

    private static void checkSingletonIdentity(TestHarness t, String label, InstanceSupplier supplier)
            throws InterruptedException {
        final int threads = 16;
        DoubleCheckedLocking.Helper[] results = new DoubleCheckedLocking.Helper[threads];
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> results[idx] = supplier.get());
            ts[i].start();
        }
        for (Thread th : ts) th.join();

        boolean allSame = true;
        for (DoubleCheckedLocking.Helper h : results) {
            if (h != results[0]) allSame = false;
        }
        t.check(label + ": all threads observe the same instance", allSame);
        t.checkEquals(label + ": singleton value is 42", 42, results[0].value);
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
