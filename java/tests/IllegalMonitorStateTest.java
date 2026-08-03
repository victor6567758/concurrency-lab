/** Verifies the exact behavior explained in the README's intro section:
 *  wait()/notify()/notifyAll() throw IllegalMonitorStateException when
 *  called without holding the object's monitor, and succeed when called
 *  from inside a synchronized block on that object. Compare with
 *  cpp/tests -- there is no C++ equivalent test, because the analogous
 *  C++ misuse (calling condition_variable::wait with an unlocked
 *  unique_lock) is undefined behavior, not a checked, testable failure. */
public class IllegalMonitorStateTest {

    static int run() {
        TestHarness t = new TestHarness();
        System.out.println("IllegalMonitorStateTest");

        Object lock = new Object();

        boolean threwOnNotify = false;
        try {
            lock.notify();
        } catch (IllegalMonitorStateException e) {
            threwOnNotify = true;
        }
        t.check("notify() outside synchronized throws IllegalMonitorStateException", threwOnNotify);

        boolean threwOnWait = false;
        try {
            lock.wait();
        } catch (IllegalMonitorStateException e) {
            threwOnWait = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        t.check("wait() outside synchronized throws IllegalMonitorStateException", threwOnWait);

        boolean threwOnNotifyAll = false;
        try {
            lock.notifyAll();
        } catch (IllegalMonitorStateException e) {
            threwOnNotifyAll = true;
        }
        t.check("notifyAll() outside synchronized throws IllegalMonitorStateException", threwOnNotifyAll);

        boolean noThrowInsideSynchronized = true;
        synchronized (lock) {
            try {
                lock.notifyAll();
            } catch (IllegalMonitorStateException e) {
                noThrowInsideSynchronized = false;
            }
        }
        t.check("notifyAll() inside synchronized does not throw", noThrowInsideSynchronized);

        return t.summary("IllegalMonitorStateTest");
    }

    public static void main(String[] args) {
        System.exit(run());
    }
}
