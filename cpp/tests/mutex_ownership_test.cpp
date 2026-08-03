#include "test_harness.hpp"
#include <pthread.h>
#include <cstring>

// Unlike the negative tests (which prove a bug via ThreadSanitizer
// because the bug itself is undefined behavior), the behavior
// mutex_ownership_demo.cpp demonstrates is NOT undefined: POSIX
// specifies exactly what PTHREAD_MUTEX_ERRORCHECK does when you
// unlock a mutex your thread doesn't own. That makes it a normal,
// assertable, deterministic test -- no sanitizer or repeated trials
// needed.
int main() {
    TestHarness t;
    std::cout << "MutexOwnershipTest\n";

    pthread_mutex_t mutex;
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_ERRORCHECK);
    pthread_mutex_init(&mutex, &attr);

    int rc = pthread_mutex_unlock(&mutex); // never locked by this thread
    t.check("error-checking mutex: unlock without owning returns a nonzero error code",
            rc != 0);
    t.checkEquals("error-checking mutex: the specific error is EPERM", EPERM, rc);

    // And the success path: lock, then unlock while actually owning it
    // should return 0, same as any other mutex type.
    int lockRc = pthread_mutex_lock(&mutex);
    t.checkEquals("locking succeeds", 0, lockRc);
    int unlockRc = pthread_mutex_unlock(&mutex);
    t.checkEquals("unlocking while owning it succeeds", 0, unlockRc);

    pthread_mutex_destroy(&mutex);
    pthread_mutexattr_destroy(&attr);

    return t.summary("MutexOwnershipTest");
}
