// Compares a manual CAS loop against std::atomic<int>::fetch_add,
// and shows off relaxed ordering -- something Java's atomics never
// expose (java.util.concurrent.atomic is always effectively seq_cst).
// See include/cas_counter.hpp for manualCasIncrement itself (extracted
// there so tests/cas_counter_test.cpp can exercise it directly).

#include "cas_counter.hpp"
#include <thread>
#include <vector>
#include <iostream>
#include <chrono>

constexpr int THREADS = 8;
constexpr int INCREMENTS_PER_THREAD = 200'000;

template <typename Fn>
long long runAndTime(const char* label, Fn task) {
    std::vector<std::thread> threads;
    auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < THREADS; ++i) threads.emplace_back(task);
    for (auto& t : threads) t.join();
    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                         std::chrono::steady_clock::now() - start).count();
    std::cout << label << " took " << elapsedMs << " ms\n";
    return elapsedMs;
}

int main() {
    std::atomic<int> manual{0};
    std::atomic<int> builtin{0};

    runAndTime("manual CAS loop (relaxed)      ", [&] {
        for (int i = 0; i < INCREMENTS_PER_THREAD; ++i) manualCasIncrement(manual);
    });
    runAndTime("fetch_add (relaxed)            ", [&] {
        for (int i = 0; i < INCREMENTS_PER_THREAD; ++i)
            builtin.fetch_add(1, std::memory_order_relaxed);
    });

    std::cout << "manual  = " << manual.load() << "\n";
    std::cout << "builtin = " << builtin.load() << "\n";
    return 0;
}
