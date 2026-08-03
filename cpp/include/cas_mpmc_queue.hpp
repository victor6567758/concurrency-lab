#pragma once
// A bounded, lock-free, multi-producer/multi-consumer queue -- unlike
// spsc_ring_buffer.hpp (exactly one producer, one consumer, no CAS
// needed -- see that file's comment for why) or blocking_queue.hpp
// (any number of producers/consumers, but every push/pop takes a
// mutex and can put a thread to sleep), this handles multiple
// producers AND multiple consumers WITHOUT ever blocking a thread.
//
// This is Dmitry Vyukov's bounded MPMC ring buffer algorithm. Every
// slot carries its own `sequence` counter alongside its data, and a
// thread claims a slot with a compare_exchange on the shared
// enqueue/dequeue index -- if another thread claimed it first, the
// CAS just fails and the loop re-reads the index and retries. No
// lock, no blocked thread, ever.
//
// Why CAS is unavoidable here (and wasn't in spsc_ring_buffer.hpp):
// with a single producer, only one thread ever writes `tail`, so a
// plain release-store is enough -- there's no write/write race to
// resolve. With MULTIPLE producers, two threads can race to claim the
// same slot; something has to atomically decide which one wins.
// compare_exchange_weak IS that decision: "if the index is still what
// I last saw, advance it -- otherwise tell me what it actually is
// now, and I'll retry against that."
//
// See README section 10 for a head-to-head benchmark against
// blocking_queue.hpp and what it shows about when CAS is worth it.

#include <atomic>
#include <vector>
#include <cstddef>
#include <optional>
#include <stdexcept>

template <typename T>
class CasMpmcQueue {
    struct Cell {
        std::atomic<std::size_t> sequence;
        T data;
    };

    std::vector<Cell> buffer;
    std::size_t mask;

    alignas(64) std::atomic<std::size_t> enqueuePos{0};
    alignas(64) std::atomic<std::size_t> dequeuePos{0};

public:
    explicit CasMpmcQueue(std::size_t capacityPowerOfTwo)
        : buffer(capacityPowerOfTwo), mask(capacityPowerOfTwo - 1) {
        if ((capacityPowerOfTwo & (capacityPowerOfTwo - 1)) != 0) {
            throw std::invalid_argument("capacity must be a power of two");
        }
        for (std::size_t i = 0; i < capacityPowerOfTwo; ++i) {
            buffer[i].sequence.store(i, std::memory_order_relaxed);
        }
    }

    // Non-blocking. Returns false immediately if the queue is full.
    bool offer(const T& item) {
        std::size_t pos = enqueuePos.load(std::memory_order_relaxed);
        Cell* cell;
        for (;;) {
            cell = &buffer[pos & mask];
            std::size_t seq = cell->sequence.load(std::memory_order_acquire);
            std::intptr_t diff =
                static_cast<std::intptr_t>(seq) - static_cast<std::intptr_t>(pos);
            if (diff == 0) {
                // Slot is free for this lap -- try to claim it. If some
                // other producer's CAS already advanced enqueuePos, this
                // fails harmlessly (compare_exchange_weak writes the
                // current value back into `pos`) and we just loop again.
                if (enqueuePos.compare_exchange_weak(
                        pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                return false; // full: consumers haven't freed a slot yet
            } else {
                pos = enqueuePos.load(std::memory_order_relaxed);
            }
        }
        cell->data = item;
        cell->sequence.store(pos + 1, std::memory_order_release); // publish
        return true;
    }

    // Non-blocking. Returns std::nullopt immediately if the queue is empty.
    std::optional<T> poll() {
        std::size_t pos = dequeuePos.load(std::memory_order_relaxed);
        Cell* cell;
        for (;;) {
            cell = &buffer[pos & mask];
            std::size_t seq = cell->sequence.load(std::memory_order_acquire);
            std::intptr_t diff =
                static_cast<std::intptr_t>(seq) - static_cast<std::intptr_t>(pos + 1);
            if (diff == 0) {
                if (dequeuePos.compare_exchange_weak(
                        pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                return std::nullopt; // empty: nothing published to this slot yet
            } else {
                pos = dequeuePos.load(std::memory_order_relaxed);
            }
        }
        T result = std::move(cell->data);
        // Ready for the NEXT lap around the ring, not this one -- adding
        // the full capacity (mask + 1) makes it match a future
        // producer's `seq - pos == 0` check.
        cell->sequence.store(pos + mask + 1, std::memory_order_release);
        return result;
    }
};
