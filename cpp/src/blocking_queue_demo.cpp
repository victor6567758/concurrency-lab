// Demo driver for BlockingQueue -- see include/blocking_queue.hpp for the
// class itself (extracted there so tests/blocking_queue_test.cpp can
// exercise it directly).

#include "blocking_queue.hpp"
#include <optional>
#include <thread>
#include <iostream>

int main() {
    constexpr int ITEMS = 10;
    BlockingQueue<std::optional<int>> queue;

    std::thread producer([&] {
        for (int i = 1; i <= ITEMS; ++i) {
            queue.push(i);
            std::cout << "produced " << i << "\n";
        }
        queue.push(std::nullopt); // poison pill
    });

    std::thread consumer([&] {
        while (true) {
            auto item = queue.pop();
            if (!item.has_value()) break;
            std::cout << "                 consumed " << *item << "\n";
        }
    });

    producer.join();
    consumer.join();
    return 0;
}
