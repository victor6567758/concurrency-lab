#include "test_harness.hpp"
#include "cache.hpp"
#include <thread>
#include <vector>

int main() {
    TestHarness t;
    std::cout << "CacheTest\n";

    Cache cache;
    cache.put("k", "v1");
    t.checkEquals("get returns what was put", std::string("v1"), cache.get("k"));

    cache.put("k", "v2");
    t.checkEquals("get returns updated value after put", std::string("v2"), cache.get("k"));

    t.checkEquals("get on missing key returns empty string", std::string(""), cache.get("missing"));

    // Concurrent readers + a writer should never deadlock or crash.
    // (shared_mutex correctness itself is the standard library's job to
    // guarantee; what we're actually checking is that our usage of it --
    // shared_lock for reads, unique_lock for writes -- doesn't hang.)
    {
        std::vector<std::thread> readers;
        for (int i = 0; i < 8; ++i) {
            readers.emplace_back([&] {
                for (int j = 0; j < 2000; ++j) cache.get("k");
            });
        }
        std::thread writer([&] {
            for (int j = 0; j < 2000; ++j) cache.put("k", "v" + std::to_string(j));
        });
        for (auto& r : readers) r.join();
        writer.join();
        t.check("concurrent readers/writer complete without deadlock", true);
    }

    return t.summary("CacheTest");
}
