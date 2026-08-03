// Head-to-head throughput comparison: BlockingQueue (mutex +
// condition_variable -- producers/consumers that find the queue
// full/empty actually sleep, and get woken by notify_one()) vs
// CasMpmcQueue (no lock at all -- threads that lose a race just spin
// and retry a CAS). Same total item count, same varying numbers of
// producer/consumer threads. The Java equivalent is QueueBenchmark.java.
//
// See README section 10 for the results from a representative run and
// what they say about when CAS actually pays for itself.

#include "blocking_queue.hpp"
#include "cas_mpmc_queue.hpp"
#include <thread>
#include <vector>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <optional>
#include <algorithm>

constexpr long TOTAL_ITEMS = 2'000'000;
constexpr std::size_t QUEUE_CAPACITY = 1 << 10; // power of two, for CasMpmcQueue

long runClassic(int producers, int consumers) {
    BlockingQueue<std::optional<int>> q;
    std::atomic<long> produced{0};

    std::vector<std::thread> prod;
    for (int p = 0; p < producers; ++p) {
        prod.emplace_back([&] {
            long n;
            while ((n = produced.fetch_add(1, std::memory_order_relaxed)) < TOTAL_ITEMS) {
                q.push(static_cast<int>(n));
            }
        });
    }
    // Consumers stop on a poison pill (std::nullopt), not a shared
    // counter -- see queue_benchmark's Java twin for why a
    // counter-based stop condition on a *blocking* pop() risks
    // deadlock.
    std::vector<std::thread> cons;
    for (int c = 0; c < consumers; ++c) {
        cons.emplace_back([&] {
            while (true) {
                auto item = q.pop();
                if (!item.has_value()) return;
            }
        });
    }

    auto start = std::chrono::steady_clock::now();
    for (auto& t : prod) t.join();
    for (int c = 0; c < consumers; ++c) q.push(std::nullopt);
    for (auto& t : cons) t.join();
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now() - start)
        .count();
}

long runCas(int producers, int consumers) {
    CasMpmcQueue<int> q(QUEUE_CAPACITY);
    std::atomic<long> produced{0};
    std::atomic<long> consumedCount{0};

    std::vector<std::thread> prod;
    for (int p = 0; p < producers; ++p) {
        prod.emplace_back([&] {
            long n;
            while ((n = produced.fetch_add(1, std::memory_order_relaxed)) < TOTAL_ITEMS) {
                while (!q.offer(static_cast<int>(n))) {
                    std::this_thread::yield();
                }
            }
        });
    }
    std::vector<std::thread> cons;
    for (int c = 0; c < consumers; ++c) {
        cons.emplace_back([&] {
            while (consumedCount.load(std::memory_order_relaxed) < TOTAL_ITEMS) {
                auto item = q.poll();
                if (!item.has_value()) {
                    std::this_thread::yield();
                    continue;
                }
                consumedCount.fetch_add(1, std::memory_order_relaxed);
            }
        });
    }

    auto start = std::chrono::steady_clock::now();
    for (auto& t : prod) t.join();
    for (auto& t : cons) t.join();
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now() - start)
        .count();
}

int main() {
    int configs[][2] = {{1, 1}, {2, 2}, {4, 4}, {8, 8}};

    std::printf("%-10s %-10s %-24s %-24s\n", "producers", "consumers",
                "classic (lock+condvar)", "CAS (lock-free)");
    for (auto& cfg : configs) {
        long classicMs = runClassic(cfg[0], cfg[1]);
        long casMs = runCas(cfg[0], cfg[1]);
        char classicBuf[64], casBuf[64];
        std::snprintf(classicBuf, sizeof(classicBuf), "%ld ms (%ld items/ms)",
                      classicMs, TOTAL_ITEMS / std::max(1L, classicMs));
        std::snprintf(casBuf, sizeof(casBuf), "%ld ms (%ld items/ms)",
                      casMs, TOTAL_ITEMS / std::max(1L, casMs));
        std::printf("%-10d %-10d %-24s %-24s\n", cfg[0], cfg[1], classicBuf, casBuf);
    }
    return 0;
}
