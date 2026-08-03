/** Same suite as cpp/tests/cache_test.cpp -- see that file for the C++
 *  side of this exact comparison. */
public class RwLockCacheTest {

    static int run() throws InterruptedException {
        TestHarness t = new TestHarness();
        System.out.println("RwLockCacheTest");

        RwLockCache cache = new RwLockCache();
        cache.put("k", "v1");
        t.checkEquals("get returns what was put", "v1", cache.get("k"));

        cache.put("k", "v2");
        t.checkEquals("get returns updated value after put", "v2", cache.get("k"));

        t.checkEquals("get on missing key returns null", null, cache.get("missing"));

        // Concurrent readers + a writer should never deadlock or throw.
        // (ReentrantReadWriteLock's correctness is the JDK's job to
        // guarantee; what we're checking is that our own read/write lock
        // usage doesn't hang.)
        Thread[] readers = new Thread[8];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                for (int j = 0; j < 2000; j++) cache.get("k");
            });
            readers[i].start();
        }
        Thread writer = new Thread(() -> {
            for (int j = 0; j < 2000; j++) cache.put("k", "v" + j);
        });
        writer.start();

        for (Thread r : readers) r.join(5000);
        writer.join(5000);

        boolean allDone = true;
        for (Thread r : readers) if (r.isAlive()) allDone = false;
        if (writer.isAlive()) allDone = false;
        t.check("concurrent readers/writer complete without deadlock", allDone);

        return t.summary("RwLockCacheTest");
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run());
    }
}
