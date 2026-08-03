import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Since Java 9 (JEP 193), java.lang.invoke.VarHandle exposes the same
 * fine-grained access modes C++'s <atomic> gives you explicitly:
 *
 *   VarHandle mode      ~ C++ memory_order
 *   ---------------------------------------------
 *   plain get/set       ~ ordinary (non-atomic) access, no ordering
 *   getOpaque/setOpaque  ~ memory_order_relaxed  (atomicity, no ordering)
 *   getAcquire/setRelease~ memory_order_acquire / memory_order_release
 *   volatile get/set     ~ memory_order_seq_cst
 *
 * Before Java 9, `volatile` (always seq_cst-like) and the internal,
 * unsupported sun.misc.Unsafe class were the only ways to get any of
 * this in Java -- VarHandle is what finally brought a public,
 * standard API with an explicit ordering dial, about 5 years after
 * C++11 did the equivalent for C++.
 *
 * VarHandle ALSO exposes standalone fences, decoupled from any one
 * particular variable -- the direct analog of C++'s
 * std::atomic_thread_fence (see cpp/src/fences_demo.cpp):
 *
 *   VarHandle.acquireFence() / releaseFence() / fullFence()
 *   VarHandle.loadLoadFence() / storeStoreFence()
 *
 * A fence orders "everything around this point in program order,"
 * rather than being tied to the one memory access an acquire load or
 * release store carries its ordering on. That's useful when you're
 * publishing several plain/opaque values through a single flag and
 * don't want to (or can't) attach acquire/release semantics to every
 * individual field access.
 */
public class FencesDemo {

    static int payload;
    static int morePayload;
    static int ready; // plain field -- we publish it "by hand" via VarHandle, not volatile

    static final VarHandle READY;
    static {
        try {
            READY = MethodHandles.lookup()
                    .findStaticVarHandle(FencesDemo.class, "ready", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void produce() {
        payload = 42;
        morePayload = 43;
        VarHandle.releaseFence();       // orders BOTH plain writes above against...
        READY.setOpaque(1);              // ...this opaque (relaxed-atomicity) publish
    }

    static void consume() {
        while ((int) READY.getOpaque() == 0) { // opaque (relaxed-atomicity) read
            Thread.onSpinWait();
        }
        VarHandle.acquireFence();        // ...paired with this fence
        System.out.println("Consumer saw payload = " + payload + ", morePayload = " + morePayload);
    }

    public static void main(String[] args) throws InterruptedException {
        Thread consumer = new Thread(FencesDemo::consume);
        consumer.start();
        Thread.sleep(50); // give the consumer a head start spinning on `ready`
        produce();
        consumer.join();

        // A standalone full fence -- the seq_cst analog -- with no
        // particular variable attached to it at all:
        VarHandle.fullFence();
        System.out.println("fullFence() executed: orders all prior loads/stores against all later ones");
    }
}
