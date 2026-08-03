// NEGATIVE TEST -- this file is intentionally buggy.
//
// This is the broken twin of volatile_publication.cpp: identical
// producer/consumer publish pattern, but with `payload` and `ready`
// as PLAIN int/bool instead of std::atomic. That means the
// producer's write to `ready` and the consumer's read of it are
// completely unsynchronized -- a textbook data race, which is
// undefined behavior in C++.
//
// You cannot write a normal assert-based test for this: UB has no
// well-defined wrong answer to check for. It might print 42 every
// time on this compiler and CPU, or print 0, or hang forever, or (in
// principle) do something stranger than either. That unpredictability
// IS the bug -- and it's exactly why "it passed my tests" is not
// evidence this pattern is safe.
//
// What you CAN do instead is what this project's CMake wires up: run
// this exact binary under Clang/GCC's ThreadSanitizer (`-fsanitize=thread`),
// which instruments every memory access and reports the race directly,
// deterministically, regardless of which value happens to get printed.
// This is the accepted engineering answer to "how do you test for a
// data race in C++": not an assertion on the outcome, but a race
// detector on the access pattern itself.
//
// A "PASS" for this test means ThreadSanitizer's report was found in
// the output -- i.e., the tool caught the exact bug this file exists
// to demonstrate. See cpp/CMakeLists.txt's NEGATIVE_TESTS section and
// the README's "Negative tests" section for how that's wired up.
//
// We repeat the race many times, and add cheap busy-work between the
// write and the flag flip, purely to widen the race window and give
// ThreadSanitizer (and, without it, an unlucky reordering) the best
// possible chance to actually manifest within one run -- a common,
// legitimate technique for making an intermittent bug reproducible
// under test.
//
// The consumer's wait loop is deliberately BOUNDED (a counted spin,
// not `while (!ready) {}` forever). That's not just test hygiene: with
// `ready` as a plain, unsynchronized bool, the compiler is entitled to
// assume no other thread can change it and hoist the read out of the
// loop entirely, turning an unbounded wait into a genuine infinite
// hang -- we observed exactly this in practice while building this
// file. That hang is itself a real, valid manifestation of the same
// bug (not a separate one), but an automated test needs to terminate,
// so we cap the spin and report "gave up" as one of the possible
// (all equally legitimate) outcomes of undefined behavior.

#include <thread>
#include <iostream>

int payload = 0;    // plain int -- NOT atomic
bool ready = false; // plain bool -- NOT atomic

volatile int sink = 0; // prevents the busy-work loop from being optimized away

void produce() {
    payload = 42;                 // (1) plain write
    for (int i = 0; i < 1000; ++i) sink += i; // widen the race window
    ready = true;                  // (2) plain write -- races with the consumer's read
}

void consume() {
    long spins = 0;
    while (!ready && spins < 5'000'000L) { // (3) plain read -- races with the producer's write
        ++spins; // bounded: see the file-level comment on why this isn't `while (!ready) {}`
    }
    if (ready) {
        std::cout << "payload = " << payload << "\n";
    } else {
        std::cout << "consumer gave up waiting -- never observed `ready` become true "
                     "(a real, legitimate outcome of this exact bug)\n";
    }
}

int main() {
    constexpr int ROUNDS = 20;
    for (int round = 0; round < ROUNDS; ++round) {
        payload = 0;
        ready = false;
        std::thread consumer(consume);
        std::thread producer(produce);
        producer.join();
        consumer.join();
    }
    return 0;
}
