#pragma once
// A hand-rolled seqlock -- the classic lock-free-read pattern that
// Java's java.util.concurrent.locks.StampedLock standardizes as
// "optimistic read" (see java/StampedLockDemo.java). C++ has no
// standard-library equivalent; this is what you write by hand (or
// pull from Boost) to get the same thing.
//
// Protocol:
//  - Writers take a real mutex (mutual exclusion against OTHER
//    writers only) and bracket their write with a sequence counter:
//    odd means "a write is in progress," even means "quiescent."
//  - A reader takes NO lock. It reads the sequence number, reads the
//    guarded data, then reads the sequence number again. If both
//    reads match and the value is even, nothing interfered and the
//    snapshot is consistent; otherwise, retry.
//
// Caveat worth knowing: whether this exact pattern is *strictly*
// well-defined under the abstract C++ memory model (as opposed to
// "correct in practice on every real CPU/compiler," which it is) has
// been an open discussion among WG21 members -- plain acquire/release
// on the sequence counter is the widely-used real-world approach (this
// is, e.g., how it's commonly written in the Linux kernel and in
// countless blog posts), but formally proving it airtight for
// arbitrary compiler optimizations turns out to be subtler than it
// looks. Treat this implementation as "the conventional version,"
// not as a from-first-principles proof.

#include <atomic>
#include <mutex>
#include <cstdint>

template <typename T>
class SeqLock {
    mutable std::atomic<uint64_t> seq{0};
    mutable std::mutex writerMutex; // serializes writers only, never readers
    T data{};

public:
    void write(const T& value) {
        std::lock_guard<std::mutex> lock(writerMutex);
        seq.fetch_add(1, std::memory_order_release); // now odd: "write in progress"
        data = value;
        seq.fetch_add(1, std::memory_order_release); // now even again: "quiescent"
    }

    // Optimistic, lock-free read. Retries internally until it observes
    // a consistent snapshot -- the same idea as Java's
    // tryOptimisticRead() + validate() loop, just wrapped into one call.
    T read() const {
        while (true) {
            uint64_t s1 = seq.load(std::memory_order_acquire);
            if (s1 & 1) continue;              // writer mid-flight; don't even bother reading
            T snapshot = data;                  // may race with a writer -- checked below
            uint64_t s2 = seq.load(std::memory_order_acquire);
            if (s1 == s2) return snapshot;      // no writer overlapped: consistent
            // else: a write happened during our read; retry
        }
    }
};
