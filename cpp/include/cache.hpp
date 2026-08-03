#pragma once
// A cache guarded by std::shared_mutex (C++17): many concurrent readers
// via shared_lock, exclusive writers via unique_lock. Compare with
// java/RwLockCache.java -- note the RAII lock guards here mean there's
// no try/finally-equivalent boilerplate; the mutex unlocks automatically
// when the guard goes out of scope, even if an exception is thrown.

#include <shared_mutex>
#include <mutex>
#include <unordered_map>
#include <string>

class Cache {
    mutable std::shared_mutex rw;
    std::unordered_map<std::string, std::string> map;
public:
    std::string get(const std::string& key) const {
        std::shared_lock lock(rw); // multiple readers OK
        auto it = map.find(key);
        return it != map.end() ? it->second : std::string{};
    }
    void put(const std::string& key, std::string value) {
        std::unique_lock lock(rw); // exclusive writer
        map[key] = std::move(value);
    }
};
