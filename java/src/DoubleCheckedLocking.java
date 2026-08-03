/**
 * Double-checked locking (DCL) in Java: broken, fixed, and obsolete-by-design.
 *
 * BROKEN version (pre-JSR-133 mental model, or if you forget `volatile`):
 *   The reference `helper` is a plain field. The JIT/CPU is free to make
 *   the write to `helper` visible to another thread BEFORE the writes
 *   inside Helper's constructor are visible to that same thread. A
 *   second thread can then see a non-null `helper` whose `value` field
 *   still reads as 0 (its default), or read a stale cached copy of
 *   `helper` forever and never see the assignment at all.
 *
 * FIXED version: declaring `helper` as `volatile` is the entire fix.
 *   Since JSR-133 (Java 5, 2004) a volatile write is a release and a
 *   volatile read is the matching acquire, so once a thread observes
 *   the non-null reference it is also guaranteed to observe every write
 *   that happened-before the store of that reference -- including the
 *   constructor's writes.
 *
 * HOLDER idiom: sidesteps DCL entirely by relying on the JLS guarantee
 *   that class initialization is thread-safe and happens-before first
 *   use. No volatile, no synchronized on the hot path, ever.
 */
public class DoubleCheckedLocking {

    static class Helper {
        int value; // deliberately NOT final, to make the hazard reachable
        Helper(int v) {
            // Simulate a slightly expensive, multi-step construction so the
            // race window is easier to hit in practice.
            value = v;
        }
    }

    static class BrokenSingleton {
        private static Helper helper; // <-- no volatile: this is the bug
        static Helper getInstance() {
            if (helper == null) {
                synchronized (BrokenSingleton.class) {
                    if (helper == null) {
                        helper = new Helper(42);
                    }
                }
            }
            return helper;
        }
    }

    static class FixedSingleton {
        private static volatile Helper helper; // <-- the one-word fix
        static Helper getInstance() {
            Helper h = helper;              // 1 volatile read on the fast path
            if (h == null) {
                synchronized (FixedSingleton.class) {
                    h = helper;
                    if (h == null) {
                        helper = h = new Helper(42);
                    }
                }
            }
            return h;
        }
    }

    static class HolderSingleton {
        // Not loaded until getInstance() is first called (lazy),
        // and the JLS guarantees thread-safe, exactly-once initialization.
        private static class Lazy {
            static final Helper INSTANCE = new Helper(42);
        }
        static Helper getInstance() {
            return Lazy.INSTANCE;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Fixed singleton value:  " + FixedSingleton.getInstance().value);
        System.out.println("Holder singleton value: " + HolderSingleton.getInstance().value);

        // Note: reliably *demonstrating* BrokenSingleton failing requires
        // a specific compiler/CPU and a lot of luck/iterations -- the JMM
        // permits the bad reordering, it doesn't guarantee you'll observe
        // it on any given run. That unpredictability is exactly what makes
        // this class of bug so dangerous: it can pass every test you run
        // and still be wrong.
        System.out.println("Broken singleton value: " + BrokenSingleton.getInstance().value
                + "  (correctness here is not guaranteed by the JMM -- see comments)");
    }
}
