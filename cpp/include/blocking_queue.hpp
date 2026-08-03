#pragma once
// Unlike Java, the C++ standard library has NO blocking queue at all
// (as of C++23) -- java.util.concurrent.ArrayBlockingQueue's behavior
// has to be built from a std::mutex + std::condition_variable, as
// shown here. In real projects you'd typically reach for a library
// (moodycamel::ConcurrentQueue, Boost, Intel TBB) instead of
// re-implementing this every time.

#include <mutex>
#include <condition_variable>
#include <queue>
#include <utility>

template <typename T>
class BlockingQueue {
    std::queue<T> q;
    mutable std::mutex m;
    std::condition_variable cv;
public:
    void push(T item) {
        {
            std::lock_guard<std::mutex> lock(m);
            q.push(std::move(item));
        }
        cv.notify_one();
    }
    T pop() {
        std::unique_lock<std::mutex> lock(m);
        cv.wait(lock, [this] { return !q.empty(); }); // handles spurious wakeups
        T item = std::move(q.front());
        q.pop();
        return item;
    }
    // Non-blocking size check -- test-only convenience, not part of the
    // core producer/consumer API.
    std::size_t size() const {
        std::lock_guard<std::mutex> lock(m);
        return q.size();
    }
};
