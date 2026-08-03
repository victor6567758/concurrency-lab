#include "test_harness.hpp"
#include "cas_counter.hpp"
#include <atomic>
#include <thread>
#include <vector>

int main() {
    TestHarness t;
    std::cout << "CasCounterTest\n";

    // Single-threaded sanity check.
    {
        std::atomic<int> counter{0};
        for (int i = 0; i < 1000; ++i) manualCasIncrement(counter);
        t.checkEquals("manual CAS loop reaches 1000 single-threaded", 1000, counter.load());
    }

    // Under real contention: no lost updates. This is the actual property
    // a CAS loop promises -- if this ever fails, the retry loop is broken.
    {
        constexpr int THREADS = 8;
        constexpr int PER_THREAD = 20000;
        std::atomic<int> counter{0};
        std::vector<std::thread> threads;
        for (int i = 0; i < THREADS; ++i) {
            threads.emplace_back([&] {
                for (int j = 0; j < PER_THREAD; ++j) manualCasIncrement(counter);
            });
        }
        for (auto& th : threads) th.join();
        t.checkEquals("manual CAS loop under contention: no lost updates",
                      THREADS * PER_THREAD, counter.load());
    }

    // fetch_add should give the identical guarantee with less code.
    {
        constexpr int THREADS = 8;
        constexpr int PER_THREAD = 20000;
        std::atomic<int> counter{0};
        std::vector<std::thread> threads;
        for (int i = 0; i < THREADS; ++i) {
            threads.emplace_back([&] {
                for (int j = 0; j < PER_THREAD; ++j)
                    counter.fetch_add(1, std::memory_order_relaxed);
            });
        }
        for (auto& th : threads) th.join();
        t.checkEquals("fetch_add under contention: no lost updates",
                      THREADS * PER_THREAD, counter.load());
    }

    return t.summary("CasCounterTest");
}
