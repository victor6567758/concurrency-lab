#include "seqlock.hpp"
#include <thread>
#include <iostream>

// x and y are always written together with the same value, so any
// consistent snapshot must have x == y. Compare with Point in
// java/StampedLockDemo.java, which enforces the same invariant.
struct Point { double x, y; };

int main() {
    SeqLock<Point> pointLock;

    std::thread writer([&] {
        for (int i = 0; i < 100000; ++i) {
            pointLock.write(Point{double(i), double(i)});
        }
    });
    std::thread reader([&] {
        Point last{};
        for (int i = 0; i < 100000; ++i) {
            last = pointLock.read();
        }
        std::cout << "last observed point = (" << last.x << ", " << last.y << ")\n";
    });
    writer.join();
    reader.join();

    Point finalPoint = pointLock.read();
    std::cout << "final point = (" << finalPoint.x << ", " << finalPoint.y << ")\n";
    return 0;
}
