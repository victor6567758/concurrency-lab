#pragma once
// A single-producer/single-consumer lock-free ring buffer. Only correct
// with exactly one producer thread and one consumer thread. Compare
// with java/SpscRingBuffer.java -- the algorithm is identical, but here
// we get explicit control over memory ordering AND over memory layout
// (cache-line padding), neither of which Java exposes directly.
//
// Why it's safe without locks:
//  - `tail` is only ever written by the producer, `head` only by the
//    consumer -- no write/write race on either index.
//  - The producer writes the slot, THEN publishes by storing the new
//    `tail` with memory_order_release. The consumer loads `tail` with
//    memory_order_acquire (to check for data) THEN reads the slot. The
//    release/acquire pair guarantees the slot write happens-before the
//    consumer's read of it.
//  - `alignas(64)` pads `head` and `tail` onto separate cache lines.
//    Without this, both indices could share one cache line, and every
//    write to `tail` by the producer would invalidate that line in the
//    consumer's cache (and vice versa) even though the two variables
//    are logically independent -- a performance bug called "false
//    sharing." Java has no direct language equivalent; the closest is
//    the internal-use-only @Contended annotation on the JDK classpath.

#include <atomic>
#include <array>
#include <cstddef>

template <typename T, std::size_t Capacity>
class SpscRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

    std::array<T, Capacity> buffer{};

    alignas(64) std::atomic<std::size_t> head{0}; // next slot consumer will read
    alignas(64) std::atomic<std::size_t> tail{0}; // next slot producer will write

public:
    // Producer-only. Returns false if the buffer is full.
    bool push(const T& item) {
        std::size_t currentTail = tail.load(std::memory_order_relaxed);
        std::size_t currentHead = head.load(std::memory_order_acquire);
        if (currentTail - currentHead >= Capacity) {
            return false; // full: consumer hasn't caught up yet
        }
        buffer[currentTail & (Capacity - 1)] = item;
        tail.store(currentTail + 1, std::memory_order_release); // publish
        return true;
    }

    // Consumer-only. Returns false if the buffer is empty.
    bool pop(T& out) {
        std::size_t currentHead = head.load(std::memory_order_relaxed);
        std::size_t currentTail = tail.load(std::memory_order_acquire);
        if (currentHead == currentTail) {
            return false; // empty: producer hasn't published anything new
        }
        out = buffer[currentHead & (Capacity - 1)];
        head.store(currentHead + 1, std::memory_order_release);
        return true;
    }
};
