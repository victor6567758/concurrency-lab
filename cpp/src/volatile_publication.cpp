// Demonstrates safe publication using std::atomic release/acquire.
//
// NOTE: C++ `volatile` is NOT a threading primitive -- it only stops the
// compiler from optimizing away accesses to memory-mapped I/O. Using it
// here for cross-thread visibility (as one might, coming from Java) is a
// classic bug. The correct tool is std::atomic with explicit ordering.
//
// The writer publishes `payload` (a plain int) and then stores `true`
// into an atomic<bool> using memory_order_release. The reader spins on
// an memory_order_acquire load of the same flag. That release/acquire
// pair guarantees the plain write to `payload` is visible to the reader
// once it observes `ready == true` -- exactly like Java's volatile.

#include <atomic>
#include <thread>
#include <chrono>
#include <iostream>

std::atomic<bool> ready{false};
int payload = 0;

void produce() {
    payload = 42;                                   // (1) plain write
    ready.store(true, std::memory_order_release);   // (2) release
}

void consume() {
    while (!ready.load(std::memory_order_acquire)) { // (3) acquire
        std::this_thread::yield();
    }
    std::cout << "Consumer saw payload = " << payload << "\n";
}

int main() {
    std::thread consumer(consume);
    std::this_thread::sleep_for(std::chrono::milliseconds(50)); // head start
    produce();
    consumer.join();
    return 0;
}
