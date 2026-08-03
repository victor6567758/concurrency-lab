#include "test_harness.hpp"
#include "dcl_singletons.hpp"
#include <thread>
#include <vector>

int main() {
    TestHarness t;
    std::cout << "DclSingletonsTest\n";

    // fixed:: -- every thread's first call must observe the exact same
    // instance, which is exactly what the acquire/release pairing on
    // the atomic<Helper*> is there to guarantee.
    {
        constexpr int THREADS = 16;
        std::vector<Helper*> results(THREADS, nullptr);
        std::vector<std::thread> threads;
        for (int i = 0; i < THREADS; ++i) {
            threads.emplace_back([&, i] { results[i] = fixed::getInstance(); });
        }
        for (auto& th : threads) th.join();

        bool allSame = true;
        for (auto* p : results) if (p != results[0]) allSame = false;
        t.check("fixed:: all threads observe the same instance", allSame);
        t.checkEquals("fixed:: singleton value is 42", 42, results[0]->value);
    }

    // magic_static:: -- same identity guarantee, provided by the
    // compiler for a plain function-local static, no atomics needed.
    {
        constexpr int THREADS = 16;
        std::vector<Helper*> results(THREADS, nullptr);
        std::vector<std::thread> threads;
        for (int i = 0; i < THREADS; ++i) {
            threads.emplace_back([&, i] { results[i] = &magic_static::getInstance(); });
        }
        for (auto& th : threads) th.join();

        bool allSame = true;
        for (auto* p : results) if (p != results[0]) allSame = false;
        t.check("magic_static:: all threads observe the same instance", allSame);
        t.checkEquals("magic_static:: singleton value is 42", 42, results[0]->value);
    }

    // Note: the `broken` namespace from double_checked_locking.cpp is
    // deliberately not tested here. Racing on it is a data race on a
    // raw pointer -- undefined behavior -- so there is no well-defined
    // outcome a test could assert on.

    return t.summary("DclSingletonsTest");
}
