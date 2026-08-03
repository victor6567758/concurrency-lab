// Demo driver for Cache -- see include/cache.hpp for the class itself
// (extracted there so tests/cache_test.cpp can exercise it directly).

#include "cache.hpp"
#include <thread>
#include <iostream>

int main() {
    Cache cache;
    cache.put("lang", "C++");

    auto reader = [&](const char* name) {
        for (int i = 0; i < 5; ++i) {
            std::cout << name << " read: " << cache.get("lang") << "\n";
        }
    };
    auto writer = [&] { cache.put("lang", "C++ (updated)"); };

    std::thread r1(reader, "reader-1");
    std::thread r2(reader, "reader-2");
    std::thread w(writer);
    r1.join();
    r2.join();
    w.join();
    return 0;
}
