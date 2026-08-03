/**
 * Demonstrates IllegalMonitorStateException: wait()/notify()/notifyAll()
 * on any Object must be called while the current thread holds that
 * object's intrinsic lock (i.e. inside a `synchronized` block/method on
 * that object). Calling them without the lock throws at runtime.
 *
 * WHY this is checked (and cheaply so): the JVM already has to track,
 * for every object's monitor, which thread currently owns it and how
 * many times reentrantly -- that bookkeeping is required to implement
 * `synchronized` itself (so a thread can re-enter its own lock, and so
 * the JVM knows when the *last* matching unlock actually releases it).
 * Since that ownership information already exists for free, checking it
 * before honoring a wait()/notify() call costs nothing extra, and lets
 * the JVM refuse to do something that would otherwise be meaningless:
 * wait() must atomically release the monitor and block, which makes no
 * sense to do on a monitor this thread doesn't hold. See
 * mutex_ownership_demo.cpp for why C++ makes the opposite tradeoff.
 */
public class IllegalMonitorStateDemo {
    public static void main(String[] args) {
        Object lock = new Object();

        try {
            lock.notify(); // no `synchronized (lock)` around this call
        } catch (IllegalMonitorStateException e) {
            System.out.println("notify() outside synchronized -> " + e);
        }

        try {
            lock.wait(); // no `synchronized (lock)` around this call
        } catch (IllegalMonitorStateException e) {
            System.out.println("wait() outside synchronized   -> " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // The correct way: acquire the monitor first.
        synchronized (lock) {
            lock.notifyAll(); // fine: this thread owns lock's monitor here
            System.out.println("notifyAll() inside synchronized -> OK, no exception");
        }
    }
}
