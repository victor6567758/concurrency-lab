#pragma once
// See src/double_checked_locking.cpp for the full explanatory comments.
// This header exists so tests/dcl_singletons_test.cpp can exercise the
// fixed and magic-static singletons directly. The `broken` namespace is
// intentionally NOT included here: exercising it under a real race is a
// data race by definition (undefined behavior), so it isn't something
// a deterministic test suite should invoke.

#include <atomic>
#include <mutex>

struct Helper {
    int value;
    explicit Helper(int v) : value(v) {}
};

namespace fixed {
    inline std::atomic<Helper*> instance{nullptr};
    inline std::mutex m;

    inline Helper* getInstance() {
        Helper* p = instance.load(std::memory_order_acquire);
        if (p == nullptr) {
            std::lock_guard<std::mutex> lock(m);
            p = instance.load(std::memory_order_relaxed); // already under lock
            if (p == nullptr) {
                p = new Helper(42);
                instance.store(p, std::memory_order_release); // publish
            }
        }
        return p;
    }
}

namespace magic_static {
    inline Helper& getInstance() {
        static Helper instance(42); // thread-safe init guaranteed since C++11
        return instance;
    }
}
