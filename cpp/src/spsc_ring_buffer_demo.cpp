#include "spsc_ring_buffer.hpp"
#include <thread>
#include <iostream>
#include <chrono>

int main() {
    constexpr int total = 5'000'000;
    static SpscRingBuffer<int, 4096> rb;

    auto start = std::chrono::steady_clock::now();

    std::thread producer([&] {
        for (int i = 0; i < total; ++i) {
            while (!rb.push(i)) {
                std::this_thread::yield(); // buffer full, back off briefly
            }
        }
    });

    std::thread consumer([&] {
        long long sum = 0;
        int item;
        for (int i = 0; i < total; ++i) {
            while (!rb.pop(item)) {
                std::this_thread::yield(); // buffer empty, back off briefly
            }
            sum += item;
        }
        std::cout << "Consumed " << total << " items, checksum = " << sum << "\n";
    });

    producer.join();
    consumer.join();

    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                         std::chrono::steady_clock::now() - start).count();
    std::cout << "Elapsed: " << elapsedMs << " ms ("
              << total / std::max<long long>(1, elapsedMs) << " items/ms)\n";
    return 0;
}
