import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** A simple cache guarded by a read-write lock: many concurrent readers,
 *  exclusive writers. Compare with cpp/rw_lock_cache.cpp. */
public class RwLockCache {

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final Map<String, String> map = new HashMap<>();

    public String get(String key) {
        rw.readLock().lock();
        try {
            return map.get(key);
        } finally {
            rw.readLock().unlock();
        }
    }

    public void put(String key, String value) {
        rw.writeLock().lock();
        try {
            map.put(key, value);
        } finally {
            rw.writeLock().unlock();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        RwLockCache cache = new RwLockCache();
        cache.put("lang", "Java");

        Runnable reader = () -> {
            for (int i = 0; i < 5; i++) {
                System.out.println(Thread.currentThread().getName() + " read: " + cache.get("lang"));
            }
        };
        Runnable writer = () -> cache.put("lang", "Java (updated)");

        Thread r1 = new Thread(reader, "reader-1");
        Thread r2 = new Thread(reader, "reader-2");
        Thread w = new Thread(writer, "writer-1");

        r1.start();
        r2.start();
        w.start();
        r1.join();
        r2.join();
        w.join();
    }
}
