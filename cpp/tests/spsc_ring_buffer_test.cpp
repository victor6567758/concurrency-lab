#include "test_harness.hpp"
#include "spsc_ring_buffer.hpp"
#include <thread>
#include <cstdint>

int main() {
    TestHarness t;
    std::cout << "SpscRingBufferTest\n";

    // Functional: fills exactly to capacity, then rejects; FIFO order preserved.
    {
        SpscRingBuffer<int, 4> rb;
        t.check("push #1 succeeds", rb.push(10));
        t.check("push #2 succeeds", rb.push(20));
        t.check("push #3 succeeds", rb.push(30));
        t.check("push #4 succeeds (buffer now full at capacity)", rb.push(40));
        t.check("push #5 fails while full", !rb.push(50));

        int out = -1;
        t.check("pop #1 succeeds", rb.pop(out));
        t.checkEquals("pop #1 returns items in FIFO order", 10, out);
        t.check("pop #2 succeeds", rb.pop(out));
        t.checkEquals("pop #2 returns items in FIFO order", 20, out);

        // Freed a slot: push should succeed again, demonstrating wraparound.
        t.check("push after pop succeeds (wraparound)", rb.push(50));

        t.check("pop #3 succeeds", rb.pop(out));
        t.checkEquals("pop #3 returns items in FIFO order", 30, out);
        t.check("pop #4 succeeds", rb.pop(out));
        t.checkEquals("pop #4 returns items in FIFO order", 40, out);
        t.check("pop #5 succeeds", rb.pop(out));
        t.checkEquals("pop #5 returns the wrapped-around item", 50, out);
        t.check("pop fails once empty", !rb.pop(out));
    }

    // Concurrent: one producer, one consumer, verify no lost/duplicated
    // items via a checksum -- this is the property the release/acquire
    // pairing on head/tail is actually there to guarantee.
    {
        constexpr int total = 200000;
        static SpscRingBuffer<int, 1024> rb;

        std::thread producer([&] {
            for (int i = 0; i < total; ++i) {
                while (!rb.push(i)) std::this_thread::yield();
            }
        });

        long long sum = 0;
        std::thread consumer([&] {
            int item;
            for (int i = 0; i < total; ++i) {
                while (!rb.pop(item)) std::this_thread::yield();
                sum += item;
            }
        });

        producer.join();
        consumer.join();

        long long expected = static_cast<long long>(total) * (total - 1) / 2;
        t.checkEquals("concurrent producer/consumer: checksum matches (no lost/dup items)",
                      expected, sum);
    }

    return t.summary("SpscRingBufferTest");
}
