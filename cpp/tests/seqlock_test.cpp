#include "test_harness.hpp"
#include "seqlock.hpp"
#include <thread>
#include <atomic>

// x and y are always written together with the same value, so any
// consistent snapshot the reader observes must have x == y. This is
// the exact same check as StampedLockTest.java -- both verify that
// the optimistic/lock-free read path never observes a torn write.
struct Point { double x = 0, y = 0; };

int main() {
    TestHarness t;
    std::cout << "SeqLockTest\n";

    SeqLock<Point> pointLock;
    constexpr int WRITES = 200000;
    std::atomic<bool> done{false};
    std::atomic<bool> invariantViolated{false};
    std::atomic<long> readsPerformed{0};

    std::thread writer([&] {
        for (int i = 0; i < WRITES; ++i) {
            pointLock.write(Point{double(i), double(i)});
        }
        done.store(true, std::memory_order_release);
    });
    std::thread reader([&] {
        while (!done.load(std::memory_order_acquire)) {
            Point p = pointLock.read();
            if (p.x != p.y) invariantViolated.store(true);
            readsPerformed.fetch_add(1);
        }
    });

    writer.join();
    reader.join();

    t.check("reader performed at least one optimistic read", readsPerformed.load() > 0);
    t.check("optimistic reads never observed a torn write (x == y always held)",
            !invariantViolated.load());

    Point finalPoint = pointLock.read();
    t.checkEquals("final x == final y", finalPoint.x, finalPoint.y);
    t.checkEquals("final value matches number of writes", double(WRITES - 1), finalPoint.x);

    return t.summary("SeqLockTest");
}
