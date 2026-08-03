// C++ has no IllegalMonitorStateException-style check by default.
// Calling std::condition_variable::wait(lock) when `lock` does not
// actually hold its mutex is undefined behavior -- not a checked
// exception -- because a plain std::mutex (unlike a Java object's
// intrinsic monitor) does not track its owning thread. There is
// nothing "for the library to check against": tracking ownership is
// exactly the extra bookkeeping C++'s zero-overhead principle says you
// shouldn't have to pay for unless you explicitly ask for it. Compare
// with the comment in IllegalMonitorStateDemo.java, where the JVM
// already has that bookkeeping for free (it needs it for `synchronized`
// reentrancy anyway), so it checks by default.
//
// POSIX gives you an OPT-IN way to get that checking:
// PTHREAD_MUTEX_ERRORCHECK. This demo uses it directly (std::mutex
// doesn't expose this mode) to show the checked behavior C++ *can*
// have, on request, but never gives you by default.

#include <pthread.h>
#include <cstdio>
#include <cstring>

int main() {
    pthread_mutex_t mutex;
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_ERRORCHECK);
    pthread_mutex_init(&mutex, &attr);

    // Try to unlock a mutex this thread never locked.
    int rc = pthread_mutex_unlock(&mutex);
    if (rc != 0) {
        std::printf("error-checking mutex: unlock without owning -> error %d (%s)\n",
                    rc, std::strerror(rc));
    } else {
        std::printf("error-checking mutex: unlock without owning -> succeeded (no check!)\n");
    }

    // By contrast, a default std::mutex gives NO such diagnostic: calling
    // unlock() without owning it, or calling condition_variable::wait()
    // with a std::unique_lock that isn't actually locked, is simply
    // undefined behavior. We deliberately do NOT demonstrate that path
    // here -- undefined behavior is, by definition, not something you
    // can reliably show. It might appear to "work," it might crash, or
    // it might silently corrupt state, and which one happens can differ
    // by compiler, standard library, and platform.

    pthread_mutex_destroy(&mutex);
    pthread_mutexattr_destroy(&attr);
    return 0;
}
