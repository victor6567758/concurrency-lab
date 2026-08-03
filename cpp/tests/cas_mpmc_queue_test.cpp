#include "test_harness.hpp"
#include "cas_mpmc_queue.hpp"
#include <thread>
#include <vector>
#include <atomic>
#include <stdexcept>

int main() {
    TestHarness t;
    std::cout << "CasMpmcQueueTest\n";

    // Single-threaded: FIFO order.
    {
        CasMpmcQueue<int> q(4);
        t.check("offer succeeds while not full (1)", q.offer(1));
        t.check("offer succeeds while not full (2)", q.offer(2));
        auto a = q.poll();
        auto b = q.poll();
        t.check("poll returns items in FIFO order (1)", a.has_value() && *a == 1);
        t.check("poll returns items in FIFO order (2)", b.has_value() && *b == 2);
    }

    // Full/empty detection.
    {
        CasMpmcQueue<int> q(2);
        t.check("offer succeeds (fills capacity)", q.offer(10));
        t.check("offer succeeds (fills capacity)", q.offer(20));
        t.check("offer fails once full", !q.offer(30));
        auto a = q.poll();
        t.check("poll returns the first item", a.has_value() && *a == 10);
        auto b = q.poll();
        t.check("poll after freeing a slot", b.has_value() && *b == 20);
        auto c = q.poll();
        t.check("poll on empty queue returns nullopt", !c.has_value());
    }

    // Constructor rejects non-power-of-two capacity.
    {
        bool threw = false;
        try {
            CasMpmcQueue<int> bad(3);
        } catch (const std::invalid_argument&) {
            threw = true;
        }
        t.check("constructor rejects non-power-of-two capacity", threw);
    }

    // MPMC stress: 4 producers, 4 consumers, no lost/duplicated items --
    // the CAS analog of blocking_queue_test.cpp's coverage, just for a
    // lock-free queue and multiple producers/consumers instead of one
    // of each.
    {
        constexpr int PRODUCERS = 4, CONSUMERS = 4, PER_PRODUCER = 20000;
        constexpr long TOTAL = PRODUCERS * PER_PRODUCER;
        CasMpmcQueue<int> q(64);
        std::atomic<long long> checksum{0};
        std::atomic<long> consumedCount{0};
        long long expectedSum = 0;
        for (int p = 0; p < PRODUCERS; ++p) {
            for (int i = 0; i < PER_PRODUCER; ++i) expectedSum += i;
        }

        std::vector<std::thread> producers;
        for (int p = 0; p < PRODUCERS; ++p) {
            producers.emplace_back([&] {
                for (int i = 0; i < PER_PRODUCER; ++i) {
                    while (!q.offer(i)) std::this_thread::yield();
                }
            });
        }
        // Non-blocking poll() makes a counter-based stopping condition
        // safe here: a consumer that finds nothing just spins and
        // re-checks, it never sleeps waiting on a wakeup that might
        // not come (unlike a blocking queue's pop() -- see
        // blocking_queue_test.cpp / ClassicBlockingQueueTest.java for
        // why those need poison pills instead).
        std::vector<std::thread> consumers;
        for (int c = 0; c < CONSUMERS; ++c) {
            consumers.emplace_back([&] {
                long long local = 0;
                while (consumedCount.load(std::memory_order_relaxed) < TOTAL) {
                    auto item = q.poll();
                    if (!item.has_value()) {
                        std::this_thread::yield();
                        continue;
                    }
                    local += *item;
                    consumedCount.fetch_add(1, std::memory_order_relaxed);
                }
                checksum.fetch_add(local, std::memory_order_relaxed);
            });
        }
        for (auto& th : producers) th.join();
        for (auto& th : consumers) th.join();

        t.checkEquals("MPMC stress: every item consumed exactly once (count)",
                      TOTAL, consumedCount.load());
        t.checkEquals("MPMC stress: checksum matches (no lost/duplicated items)",
                      expectedSum, checksum.load());
    }

    return t.summary("CasMpmcQueueTest");
}
