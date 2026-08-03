#include "test_harness.hpp"
#include <atomic>
#include <thread>

// Same style as fences_test.cpp: repeats volatile_publication.cpp's
// exact producer/consumer pattern (payload published via release,
// observed via acquire) across many fresh trials, and checks the
// consumer always sees the fully-published value. As with the fences
// test, this is a functional smoke test, not a formal race-absence
// proof -- correct code always passes it, but passing it alone doesn't
// prove the release/acquire pairing was load-bearing (only a tool like
// ThreadSanitizer running the *broken* twin, as in
// negative_tests/unsafe_publication_race.cpp, demonstrates that).
int main() {
    TestHarness t;
    std::cout << "VolatilePublicationTest\n";

    constexpr int TRIALS = 200;
    bool allCorrect = true;

    for (int trial = 0; trial < TRIALS; ++trial) {
        int payload = 0;
        std::atomic<bool> ready{false};
        int expected = trial + 1;
        int observed = -1;

        std::thread consumer([&] {
            while (!ready.load(std::memory_order_acquire)) {
                std::this_thread::yield();
            }
            observed = payload;
        });
        std::thread producer([&] {
            payload = expected;                              // (1) plain write
            ready.store(true, std::memory_order_release);    // (2) release
        });

        producer.join();
        consumer.join();

        if (observed != expected) allCorrect = false;
    }

    t.check("consumer observed the fully-published payload on all " +
                std::to_string(TRIALS) + " trials", allCorrect);

    return t.summary("VolatilePublicationTest");
}
