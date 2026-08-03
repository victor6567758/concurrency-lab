// Demo driver for CasMpmcQueue -- see include/cas_mpmc_queue.hpp for the
// class itself (extracted there so tests/cas_mpmc_queue_test.cpp can
// exercise it directly). Compare with blocking_queue_demo.cpp: same
// producer/consumer shape, but this one never blocks a thread.

#include "cas_mpmc_queue.hpp"
#include <thread>
#include <vector>
#include <iostream>
#include <chrono>
#include <atomic>

int main() {
    constexpr long total = 5'000'000;
    constexpr int producers = 4;
    constexpr int consumers = 4;
    CasMpmcQueue<int> q(1 << 12);

    std::atomic<long> produced{0};
    std::atomic<long> consumedCount{0};
    std::atomic<long long> checksum{0};

    std::vector<std::thread> prod;
    for (int p = 0; p < producers; ++p) {
        prod.emplace_back([&] {
            long n;
            while ((n = produced.fetch_add(1, std::memory_order_relaxed)) < total) {
                while (!q.offer(static_cast<int>(n))) {
                    std::this_thread::yield();
                }
            }
        });
    }

    std::vector<std::thread> cons;
    for (int c = 0; c < consumers; ++c) {
        cons.emplace_back([&] {
            long long localSum = 0;
            while (consumedCount.load(std::memory_order_relaxed) < total) {
                auto item = q.poll();
                if (!item.has_value()) {
                    std::this_thread::yield();
                    continue;
                }
                localSum += *item;
                consumedCount.fetch_add(1, std::memory_order_relaxed);
            }
            checksum.fetch_add(localSum, std::memory_order_relaxed);
        });
    }

    auto start = std::chrono::steady_clock::now();
    for (auto& t : prod) t.join();
    for (auto& t : cons) t.join();
    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                          std::chrono::steady_clock::now() - start)
                          .count();

    std::cout << "Consumed " << total << " items across " << producers
              << " producers / " << consumers << " consumers, checksum = "
              << checksum.load() << "\n";
    std::cout << "Elapsed: " << elapsedMs << " ms ("
              << (total / std::max<long long>(1, elapsedMs)) << " items/ms)\n";
    return 0;
}
