#include "test_harness.hpp"
#include "blocking_queue.hpp"
#include <thread>
#include <chrono>
#include <atomic>

int main() {
    TestHarness t;
    std::cout << "BlockingQueueTest\n";

    // FIFO order, single-threaded.
    {
        BlockingQueue<int> q;
        q.push(1);
        q.push(2);
        q.push(3);
        t.checkEquals("pop returns items in FIFO order (1)", 1, q.pop());
        t.checkEquals("pop returns items in FIFO order (2)", 2, q.pop());
        t.checkEquals("pop returns items in FIFO order (3)", 3, q.pop());
    }

    // pop() actually blocks when empty, and wakes up once an item arrives
    // -- this is the entire reason BlockingQueue exists instead of just
    // returning a sentinel. We verify this by checking that a consumer
    // started on an empty queue is still waiting after a short delay,
    // and only completes once we push.
    {
        BlockingQueue<int> q;
        std::atomic<bool> consumerReturned{false};
        int received = -1;

        std::thread consumer([&] {
            received = q.pop(); // should block here until we push
            consumerReturned = true;
        });

        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        t.check("pop() is still blocking on an empty queue after 100ms",
                !consumerReturned.load());

        q.push(42);
        consumer.join();
        t.check("pop() unblocks once an item is pushed", consumerReturned.load());
        t.checkEquals("pop() returns the pushed item", 42, received);
    }

    return t.summary("BlockingQueueTest");
}
