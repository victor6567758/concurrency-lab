#pragma once
// A bare counter increment carries no other data that needs to be
// published alongside it, so memory_order_relaxed is legal and faster:
// we only need atomicity of the increment itself, not any ordering
// relationship with other memory. See src/cas_counter.cpp for the
// fuller comparison against fetch_add.

#include <atomic>

inline void manualCasIncrement(std::atomic<int>& counter) {
    int prev = counter.load(std::memory_order_relaxed);
    int next;
    do {
        next = prev + 1;
    } while (!counter.compare_exchange_weak(
                 prev, next,
                 std::memory_order_relaxed,   // success: no ordering needed here
                 std::memory_order_relaxed)); // failure: just a retry
}
