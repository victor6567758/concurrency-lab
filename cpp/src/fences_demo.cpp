// Demonstrates std::atomic_thread_fence -- a standalone fence, not
// tied to any specific atomic variable, and the direct analog of Java
// 9's VarHandle.acquireFence()/releaseFence()/fullFence() (see
// java/FencesDemo.java).
//
// This is the "classic" way to build a publish/subscribe pattern using
// RELAXED atomics for the actual data, with the fences doing all the
// ordering work instead of attaching acquire/release directly to every
// individual load/store. Useful when you're publishing several values
// through one flag and don't want (or can't cheaply afford) full
// acquire/release semantics on each one individually -- the fence
// orders everything around it in program order, in one shot.

#include <atomic>
#include <thread>
#include <chrono>
#include <iostream>

std::atomic<int> payload{0};
std::atomic<int> morePayload{0};
std::atomic<bool> ready{false};

void produce() {
    payload.store(42, std::memory_order_relaxed);
    morePayload.store(43, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release); // orders BOTH stores above against...
    ready.store(true, std::memory_order_relaxed);          // ...this relaxed store
}

void consume() {
    while (!ready.load(std::memory_order_relaxed)) {       // relaxed load...
        std::this_thread::yield();
    }
    std::atomic_thread_fence(std::memory_order_acquire);   // ...paired with this fence
    std::cout << "payload = " << payload.load(std::memory_order_relaxed)
              << ", morePayload = " << morePayload.load(std::memory_order_relaxed) << "\n";
}

int main() {
    std::thread consumer(consume);
    std::this_thread::sleep_for(std::chrono::milliseconds(50)); // head start
    produce();
    consumer.join();

    // A standalone full (seq_cst) fence, with no particular variable
    // attached to it at all -- same idea as VarHandle.fullFence().
    std::atomic_thread_fence(std::memory_order_seq_cst);
    std::cout << "seq_cst fence executed: orders all prior loads/stores against all later ones\n";
    return 0;
}
