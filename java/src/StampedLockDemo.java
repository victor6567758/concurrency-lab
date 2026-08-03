import java.util.concurrent.locks.StampedLock;

/**
 * StampedLock (Java 8+) supports three modes: writeLock (exclusive),
 * readLock (shared, like ReentrantReadWriteLock's read lock), and
 * optimistic read -- which takes NO lock at all. tryOptimisticRead()
 * just hands back a stamp (an internal version number); after reading
 * the guarded fields, validate(stamp) checks whether any writer
 * acquired the write lock in between. If validation fails, fall back
 * to a real (blocking) read lock.
 *
 * The win: on the common case (no concurrent writer), an optimistic
 * read costs a single plain read of the stamp plus your actual field
 * reads -- no CAS, no blocking, no contention with other readers. This
 * is conceptually identical to the seqlock pattern hand-built in C++
 * (see cpp/include/seqlock.hpp) -- StampedLock is essentially
 * "seqlock, standardized and built into the JDK."
 */
public class StampedLockDemo {

    static class Point {
        private final StampedLock sl = new StampedLock();
        private double x, y;

        /** Moves x and y by the same amount, in two separate field
         *  writes -- deliberately, so an unguarded reader in between
         *  those two writes could observe x != y (a torn read). */
        void move(double d) {
            long stamp = sl.writeLock();
            try {
                x += d;
                y += d;
            } finally {
                sl.unlockWrite(stamp);
            }
        }

        /** Optimistic-read-first: try the lock-free path, and only pay
         *  for a real read lock if a writer actually interfered. */
        double[] readXY() {
            long stamp = sl.tryOptimisticRead();
            double currentX = x, currentY = y; // plain reads -- may race with a writer
            if (!sl.validate(stamp)) {
                stamp = sl.readLock();
                try {
                    currentX = x;
                    currentY = y;
                } finally {
                    sl.unlockRead(stamp);
                }
            }
            return new double[]{currentX, currentY};
        }

        double distanceFromOrigin() {
            double[] xy = readXY();
            return Math.sqrt(xy[0] * xy[0] + xy[1] * xy[1]);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Point p = new Point();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100_000; i++) p.move(1);
        });
        Thread reader = new Thread(() -> {
            double last = -1;
            for (int i = 0; i < 100_000; i++) {
                last = p.distanceFromOrigin();
            }
            System.out.println("last observed distance = " + last);
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        System.out.println("final distance = " + p.distanceFromOrigin());
    }
}
