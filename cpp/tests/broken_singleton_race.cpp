// NEGATIVE TEST -- this file is intentionally buggy.
//
// This is the standalone, stress-tested version of the `broken`
// namespace in double_checked_locking.cpp: a singleton published
// through a plain (non-atomic) raw pointer, read and written by
// multiple threads racing on `getInstance()` with no synchronization
// around the pointer itself (only the inner check is guarded by a
// mutex -- the outer, fast-path check is not).
//
// Same story as unsafe_publication_race.cpp: there is no well-defined
// wrong VALUE to assert on here. The point of this file is to be fed
// to ThreadSanitizer, which instruments the unsynchronized read/write
// of `instance` and reports the race directly. A "PASS" for this test
// means ThreadSanitizer's report was found in the output.
//
// We reset the singleton and race many threads against it repeatedly,
// purely to give the race the best possible chance to manifest within
// one run -- with only two threads and one call each, you might get
// lucky and never observe it, even though the code is just as broken.

#include <thread>
#include <vector>
#include <mutex>
#include <iostream>

struct Helper {
    int value;
    explicit Helper(int v) : value(v) {}
};

Helper* instance = nullptr; // plain pointer -- NOT atomic
std::mutex m;

Helper* getInstance() {
    if (instance == nullptr) {           // (1) unsynchronized read -- races with (3)
        std::lock_guard<std::mutex> lock(m);
        if (instance == nullptr) {
            instance = new Helper(42);   // (2) unsynchronized write -- races with (1)
        }
    }
    return instance;
}

int main() {
    constexpr int ROUNDS = 10;
    constexpr int THREADS_PER_ROUND = 8;

    for (int round = 0; round < ROUNDS; ++round) {
        instance = nullptr; // reset for this round (intentionally leaks the previous Helper)

        std::vector<std::thread> threads;
        for (int i = 0; i < THREADS_PER_ROUND; ++i) {
            threads.emplace_back([] {
                Helper* h = getInstance();
                (void) h->value; // touch it, matching how a real caller would use it
            });
        }
        for (auto& t : threads) t.join();
    }

    std::cout << "final singleton value = " << instance->value << "\n";
    return 0;
}
