// Double-checked locking (DCL) in C++: broken, fixed, and obsolete-by-design.
//
// BROKEN version: uses a raw pointer with ordinary (non-atomic) reads and
//   writes. This is THE classic example from Meyers & Alexandrescu's
//   "C++ and the Perils of Double-Checked Locking" (2004) -- the paper
//   that got the whole C++ standards committee to eventually design
//   <atomic> with explicit memory orders. Without an atomic, there is
//   NO standard-guaranteed ordering between:
//     (a) the writes inside Helper's constructor, and
//     (b) the write of the pointer `instance = p;`
//   The compiler and CPU are both free to reorder (a) and (b), so a
//   second thread can see a non-null `instance` pointing at an
//   incompletely-constructed Helper. This is a **data race on a raw
//   pointer**, which is undefined behavior in C++ -- not merely "a
//   wrong value" like the Java analog, but UB: anything is permitted,
//   including a crash, or the compiler eliding the check altogether.
//
// FIXED and MAGIC STATIC versions live in include/dcl_singletons.hpp
// (extracted there so tests/dcl_singletons_test.cpp can exercise them
// directly) -- see that header for their explanatory comments.

#include "dcl_singletons.hpp"
#include <iostream>

namespace broken {
    Helper* instance = nullptr; // plain pointer -- NOT atomic
    std::mutex m;

    Helper* getInstance() {
        if (instance == nullptr) {               // unsynchronized read: data race
            std::lock_guard<std::mutex> lock(m);
            if (instance == nullptr) {
                instance = new Helper(42);         // unsynchronized write: data race
            }
        }
        return instance;
    }
}

int main() {
    std::cout << "Fixed singleton value:        " << fixed::getInstance()->value << "\n";
    std::cout << "Magic-static singleton value: " << magic_static::getInstance().value << "\n";

    // We still call the broken path so the demo runs end-to-end, but
    // note this is technically undefined behavior the moment more than
    // one thread races on `instance` -- it may "work" on this run, this
    // compiler, this CPU, and still be wrong in the general case.
    std::cout << "Broken singleton value:       " << broken::getInstance()->value
              << "  (data race -> UB in the general case; see comments)\n";
    return 0;
}
