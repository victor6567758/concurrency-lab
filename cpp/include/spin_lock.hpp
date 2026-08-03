#pragma once
// A minimal test-and-test-and-set (TTAS) spinlock built on
// std::atomic_flag -- the one atomic type the standard guarantees is
// always lock-free on every platform (every other std::atomic<T> is
// merely "usually" lock-free in practice, checked at runtime via
// is_lock_free()). Compare with java/SpinLock.java, which does the
// identical TTAS dance on AtomicBoolean.
//
// The "test-and-TEST-and-set" part matters for performance under
// contention: spinning on a cheap relaxed *load* first, rather than
// repeatedly attempting test_and_set() itself, avoids hammering cache
// coherence. test_and_set is a read-modify-write that invalidates the
// cache line on every other core holding it, even on failure, while a
// plain load lets the line stay shared and cheap to poll until it
// actually looks free.
//
// Same caveat as the Java version: only reach for a spinlock when the
// critical section is extremely short and you have at least as many
// cores as contending threads -- otherwise a blocking mutex (which
// yields the core to someone useful) wins.

#include <atomic>
#include <thread>

class SpinLock {
    std::atomic_flag flag = ATOMIC_FLAG_INIT;
public:
    void lock() {
        while (true) {
            // Test: spin on a cheap read until it looks free.
            while (flag.test(std::memory_order_relaxed)) {
                std::this_thread::yield(); // a real implementation would use
                                            // a CPU "pause" intrinsic here instead
            }
            // ...and-set: only now attempt the actual RMW.
            if (!flag.test_and_set(std::memory_order_acquire)) {
                return; // we got it
            }
            // someone beat us to it between the read and the RMW; retry
        }
    }
    void unlock() {
        flag.clear(std::memory_order_release);
    }
};
