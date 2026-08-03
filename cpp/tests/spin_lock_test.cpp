#include "test_harness.hpp"
#include "spin_lock.hpp"
#include <thread>
#include <vector>

int main() {
    TestHarness t;
    std::cout << "SpinLockTest\n";

    SpinLock lock;
    constexpr int THREADS = 8;
    constexpr int PER_THREAD = 20000;

    int counter = 0;
    int insideCount = 0;
    bool mutualExclusionViolated = false;

    std::vector<std::thread> threads;
    for (int i = 0; i < THREADS; ++i) {
        threads.emplace_back([&] {
            for (int j = 0; j < PER_THREAD; ++j) {
                lock.lock();
                ++insideCount;
                if (insideCount != 1) mutualExclusionViolated = true;
                ++counter; // plain increment -- safe only because of the lock
                --insideCount;
                lock.unlock();
            }
        });
    }
    for (auto& th : threads) th.join();

    t.checkEquals("no lost updates under contention", THREADS * PER_THREAD, counter);
    t.check("mutual exclusion never violated (never >1 thread inside)",
            !mutualExclusionViolated);

    return t.summary("SpinLockTest");
}
