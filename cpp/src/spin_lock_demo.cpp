#include "spin_lock.hpp"
#include <thread>
#include <vector>
#include <iostream>
#include <chrono>

int main() {
    SpinLock lock;
    int counter = 0;
    constexpr int THREADS = 8;
    constexpr int PER_THREAD = 100000;

    auto start = std::chrono::steady_clock::now();
    std::vector<std::thread> threads;
    for (int i = 0; i < THREADS; ++i) {
        threads.emplace_back([&] {
            for (int j = 0; j < PER_THREAD; ++j) {
                lock.lock();
                ++counter; // plain, unsynchronized increment -- safe only because of the lock
                lock.unlock();
            }
        });
    }
    for (auto& t : threads) t.join();
    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                         std::chrono::steady_clock::now() - start).count();

    std::cout << "counter = " << counter << " (expected " << THREADS * PER_THREAD << ")\n";
    std::cout << "elapsed = " << elapsedMs << " ms\n";
    return 0;
}
