#include "test_harness.hpp"
#include <atomic>
#include <thread>

// Repeats fences_demo.cpp's publish/subscribe pattern many times and
// checks the consumer always observes the fully-published value. Same
// caveat as FencesTest.java: this is a functional smoke test, not a
// race detector -- it verifies the pattern behaves as documented
// across many fresh runs, not that the fences were strictly necessary
// on this particular hardware.
int main() {
    TestHarness t;
    std::cout << "FencesTest\n";

    constexpr int TRIALS = 50;
    bool allCorrect = true;

    for (int trial = 0; trial < TRIALS; ++trial) {
        std::atomic<int> payload{0};
        std::atomic<bool> ready{false};
        int expected = trial + 1;
        int observed = -1;

        std::thread consumer([&] {
            while (!ready.load(std::memory_order_relaxed)) std::this_thread::yield();
            std::atomic_thread_fence(std::memory_order_acquire);
            observed = payload.load(std::memory_order_relaxed);
        });
        std::thread producer([&] {
            payload.store(expected, std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_release);
            ready.store(true, std::memory_order_relaxed);
        });

        producer.join();
        consumer.join();

        if (observed != expected) allCorrect = false;
    }

    t.check("consumer observed the fully-published payload on all " +
                std::to_string(TRIALS) + " trials", allCorrect);

    return t.summary("FencesTest");
}
