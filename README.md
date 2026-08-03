# Concurrency Lab: Java vs Modern C++

A runnable, side-by-side comparison of Java and modern C++ (C++17/20)
concurrency: memory models, visibility, CAS, safe publication, locks,
concurrent queues - plus two deep dives (a lock-free SPSC ring buffer,
and double-checked locking, broken and fixed, in both languages).

Every file has a `main`/`main()` and actually runs. Nothing here is
pseudocode.

## What this is

Every concept in this repo shows up three times: a Java implementation,
a C++ implementation, and a README section explaining where the two
actually diverge and why (not just "here's the syntax difference," but
"here's the actual guarantee one language gives you for free that the
other makes you build or do without"). Each implementation comes with
a demo you can run and watch, and a test that actually checks the
guarantee holds rather than just looking plausible - the demo is for a
human to see the behavior, the test is what a CI system (or you,
skimming) should actually trust.

This is meant to be read as a guided tour, not grepped for a snippet.
Sections 0 through 12 build on each other: Section 1 (memory models)
is the concept everything after it depends on, Section 3 (negative
tests) only makes sense once you've seen a broken implementation in
Section 2, and Section 10's benchmark leans on the CAS mechanics
introduced in Section 9. If you're only here for one topic, the
file-by-file table in Section 8 is the fastest way to jump straight to
the two files that cover it.

Assumed background: comfortable reading at least one of Java or C++;
no prior concurrency expertise required; the point of this repo is
that concurrency bugs are subtle enough to deserve a runnable
side-by-side rather than a slide with "don't do this" on it.

## Table of contents

- Intro: why Java checks lock ownership and C++ doesn't
- Memory models, in one paragraph each
- Double-checked locking - broken in one, fine in the other, for interesting reasons
- Negative tests: catching broken code, in both languages, two different ways
- A lock-free SPSC ring buffer
- Fences - ordering without an attached variable
- Spin locks - when busy-waiting beats blocking
- StampedLock (Java) vs a hand-rolled Seqlock (C++)
- The rest of the comparison, file by file
- SPSC vs MPMC, and what "lock-free" actually costs you
- What CAS actually buys you - benchmarking a lock-free queue against a classic one
- One-paragraph takeaway
- References


All test files - regular and negative alike - live together under
`tests/` in both languages now. What sets the two negative tests apart
in C++ is purely how they're compiled (with `-fsanitize=thread`,
gated behind the opt-in `ENABLE_TSAN_NEGATIVE_TESTS` CMake option), not
where the source lives. Java's negative tests need no special compiler
flag or opt-in at all - they run automatically as part of the regular
suite (`make test-java` / `RunAllTests`) - which is itself one of the
findings Section 3 covers.

Both languages also share the same top-level shape now: demo/library
code under `src/`, every test (including the negative ones) under
`tests/`. `java/src/` mirrors `cpp/src/`; `java/tests/` mirrors
`cpp/tests/`.

## Building everything at once and running tests

There's a single top-level `Makefile` that wraps both languages' native
build tools into one project:

```bash
make              # build everything: Java classes + C++ binaries
make test         # run both test suites (10 Java suites, 10 C++ suites)
make test-java    # run java test suites
make test-cpp     # builds and runs the CMake/CTest suite
make test-cpp-negative   # opt-in: the two ThreadSanitizer negative tests too
make run-demos-java      # run every Java demo's main() in sequence
make run-demos-cpp       # run every C++ demo binary in sequence
make clean        # remove all build output from both languages
make help         # list every target
make build              # build both languages
make run-demos-java      # build + run every Java demo's main() in sequence
make run-demos-cpp       # build + run every C++ demo binary in sequence
make run-benchmark-java  # run QueueBenchmark (~1-2 min, not part of run-demos-java)
make run-benchmark-cpp   # run queue_benchmark (~1-2 min, not part of run-demos-cpp)
```
`make help` lists every target. Tested with JDK 21 and g++ 13
(C++20); see the Makefile for the exact underlying `javac`/`cmake`
invocations if you need to reproduce a step by hand.


What each suite actually checks (both languages test the same
properties, side by side):

| Suite | What it verifies |
|---|---|
| CAS counter | No lost updates when many threads increment concurrently - the entire point of a CAS retry loop |
| SPSC ring buffer | FIFO order, correct full/empty detection, wraparound, and a full producer/consumer run with a checksum to catch any lost or duplicated item |
| DCL singletons | Every thread's first call observes the identical instance (not just an equal one) - the property `volatile`/`atomic` release-acquire is there to guarantee. The intentionally-broken version is not exercised under a race in this suite: a race on it has no well-defined outcome an assert-based test could check. Both languages' broken versions get exercised separately instead - under ThreadSanitizer (C++) or as a best-effort empirical probe (Java) - in the negative test suite (Section 3) |
| Negative: unsafe publication | Both languages' broken twin of the "safe publication" demo, run until the bug manifests. C++'s (`unsafe_publication_race.cpp`) is caught deterministically by ThreadSanitizer. Java's (`UnsafePublicationRaceTest`) reproduces empirically - reliably, in our testing, as a genuine JIT-hoisting hang - but that's an observed fact about this JVM, not a language guarantee, unlike TSan's finding |
| Negative: broken DCL singleton | Both languages' broken twin of the DCL singleton, stress-tested across many rounds. C++'s (`broken_singleton_race.cpp`) is still caught deterministically by ThreadSanitizer regardless of whether the bad reordering actually occurs. Java's (`BrokenSingletonRaceTest`) does not reliably reproduce on x86 (0 anomalies across 800,000 observations in our testing) - and deliberately does not assert that it must, since a clean run proves nothing about correctness here. This asymmetry is the central point of Section 3 |
| Read-write lock cache | Basic get/put correctness, plus that concurrent readers and a writer complete without deadlocking |
| Blocking queue | FIFO order, and - the property that actually matters - that `pop()`/`take()` (and, on the Java side, `put()` too) genuinely blocks while empty/full and wakes up once an item moves. Java's test exercises `ArrayBlockingQueue` directly (since `BlockingQueueDemo.java` uses it inline, with nothing hand-rolled to extract); C++'s exercises the hand-rolled `BlockingQueue` from `blocking_queue.hpp` |
| Classic blocking queue (lock + condvar) | Same FIFO/blocks-when-full/blocks-when-empty checks as the row above, but against the hand-rolled `ClassicBlockingQueue` (Java: `ReentrantLock` + two `Condition`s) instead of the library class - plus an MPMC stress run (4 producers, 4 consumers) checked with a checksum for lost/duplicated items. Termination uses one poison pill per consumer, not a shared counter - see the note in Section 10 about why a counter is unsafe to pair with a blocking call |
| CAS MPMC queue (lock-free) | The lock-free analog of the row above: same FIFO/full/empty/MPMC-stress-with-checksum coverage, for `CasMpmcQueue`/`cas_mpmc_queue.hpp` (Vyukov's algorithm) instead of a lock-based queue. A counter-based stop condition IS safe here, because `poll()` never blocks - see Section 10 |
| Spin lock | No lost updates under contention, and (via an "inside the critical section" counter) that mutual exclusion is never actually violated |
| StampedLock / seqlock | A `Point{x, y}` invariant (`x == y`) that must hold for any consistent snapshot, checked across hundreds of thousands of concurrent optimistic reads against a concurrent writer - the exact property optimistic reads exist to guarantee |
| Fences | The producer/consumer publish pattern is repeated across many fresh trials and the consumer must observe the fully-published value every time - a functional smoke test, not a formal race-absence proof |
| Volatile publication | Same repeated-trials smoke test as Fences, but for the plain `volatile`/release-acquire publish pattern from Section 1 - closes what was otherwise the one demo file with no dedicated test |
| Mutex ownership | Unlike the negative tests, this one IS a normal deterministic assertion: POSIX specifies exactly what `PTHREAD_MUTEX_ERRORCHECK` returns when you unlock a mutex you don't own (`EPERM`), so no sanitizer or repeated trials are needed here |
| `IllegalMonitorStateException` (Java only) | `wait()`/`notify()`/`notifyAll()` throw when called outside `synchronized`, and don't when called inside it. No C++ equivalent test exists, because the analogous C++ misuse is undefined behavior, not a checked, testable failure - see Section 0 |

---

## Intro: why Java checks lock ownership and C++ doesn't

Before getting into memory models, one asymmetry is worth calling out
up front, because it explains a lot of the design philosophy behind
everything else here: Java enforces at runtime that you hold the
right lock before you can wait/notify on it. C++ does not.

In Java, every single object carries an intrinsic lock (its
"monitor") as part of its runtime identity - this is baked into the
object header, not something you opt into. `Object.wait()`,
`notify()`, and `notifyAll()` are methods every object has, and all
three require the calling thread to currently hold that object's
monitor (i.e., be inside a `synchronized` block/method on it).
Calling any of them without the lock throws `IllegalMonitorStateException`
immediately - see `IllegalMonitorStateDemo.java`:
```java
Object lock = new Object();
lock.notify();  // no synchronized(lock) around this
// -> java.lang.IllegalMonitorStateException: current thread is not owner
```

Why the JVM can afford to check this for free: implementing
`synchronized` at all requires the JVM to already track, per object,
which thread currently owns its monitor and how many times it has
reentered it (so the same thread can lock it again without deadlocking
itself, and so the last matching unlock is the one that actually
releases it). That bookkeeping exists regardless. Since it's already
there, checking it before honoring a `wait()`/`notify()` call costs
nothing extra - and it catches a call that would otherwise be
meaningless: `wait()`'s whole job is to atomically release the monitor
and block the calling thread, which makes no sense to do on a monitor
the thread doesn't actually hold. Throwing a clear exception here is
consistent with Java's broader safety-first stance (bounds-checked
arrays, verified bytecode, `NullPointerException` instead of a wild
pointer dereference, etc.) - fail loudly and immediately rather than
let a misuse silently corrupt program state.

C++ takes the opposite default. `std::condition_variable::wait(lock)`
also requires the calling thread to hold `lock`'s mutex - but a plain
`std::mutex` does not, by default, track which thread owns it. If you
call `wait()` on a `std::unique_lock` that isn't actually locked, or
`unlock()` a mutex your thread never locked, the standard simply says
this is undefined behavior - not a thrown exception, because there
is no ownership metadata for the library to check against in the first
place. Adding that tracking to every mutex would mean every program
pays for bookkeeping that correct programs never need - directly
against C++'s "don't pay for what you don't use" principle. See
`mutex_ownership_demo.cpp`, which shows that the checking Java gives
you for free is nonetheless available in C++ - POSIX threads support
an opt-in `PTHREAD_MUTEX_ERRORCHECK` mutex type ([POSIX spec for
`pthread_mutex_unlock`, error-checking behavior][posix-mutex]) that
does track ownership and returns a defined error (`EPERM`) instead of
invoking undefined behavior. `std::mutex` just doesn't use that mode by
default, because most C++ code prioritizes the zero-overhead common
case over a safety net for the misuse case.

| | Java (`Object.wait/notify`) | C++ (`std::condition_variable` + `std::mutex`) |
|---|---|---|
| Must hold the lock to call `wait` | Yes, enforced | Yes, required by the standard |
| Must hold the lock to call `notify` | Yes, enforced | No (recommended, not required) |
| What happens if you don't | `IllegalMonitorStateException`, always | Undefined behavior, by default |
| Why | Ownership bookkeeping already exists for `synchronized`'s reentrancy; checking it is free | Ownership bookkeeping doesn't exist by default; adding it costs every user, even correct ones |
| Opt-in checked alternative | N/A - it's already on by default | `PTHREAD_MUTEX_ERRORCHECK` (POSIX), not exposed via `std::mutex` |

This is the same theme that shows up again in the memory model
section below, and again in the double-checked-locking deep dive:
Java bakes in a safety net and always pays its (small) cost; C++ makes
the safety net optional and lets you pay for it only if you ask.

## Memory models, in one paragraph each

Java Memory Model (JMM, [JSR-133][jsr133-faq]): built around
happens-before edges. A `volatile` write happens-before a later
`volatile` read of the same field; a monitor unlock happens-before a
later lock of the same monitor; a thread's actions happen-before
another thread's `join()` returning. Without one of these edges, the
JIT and CPU may reorder, cache, or eliminate memory accesses freely -
but the result is always some value that was legally written
somewhere, never undefined behavior.

Modern C++ (C++11 onward): exposes the ordering dial directly on
every atomic operation ([`std::memory_order`][cppref-memory-order]):
`memory_order_relaxed` (atomicity only, no ordering), `acquire`/
`release` (the same pairing Java's `volatile` gives you, but opt-in
per operation), and `seq_cst` (acquire+release plus one global total
order - the default if you don't specify). A data race on non-atomic
memory in C++ is undefined behavior: not "a wrong value," but license
for the compiler to do anything.

| | Java | C++ |
|---|---|---|
| Default ordering on plain fields | none | none (not even atomic) |
| Strongest built-in tool | `volatile` / `synchronized` (≈ seq_cst per variable) | `memory_order_seq_cst` (default) |
| Fine-grained control | `volatile`/`synchronized` only until Java 9; `VarHandle` since (see History below) | `relaxed` / `acquire` / `release` / `acq_rel` / `seq_cst`, since C++11 |
| Race on ordinary data | wrong value, not UB | undefined behavior |

`volatile` false-friend warning: C++'s `volatile` keyword has
nothing to do with threading - it only disables optimizations around
memory-mapped I/O. The C++ analog of Java's `volatile` is
`std::atomic<T>`. See `volatile_publication.cpp` vs
`VolatilePublication.java`.

### A brief history of both memory models

Neither language's memory model arrived fully formed - both were
patched in (or onto) an existing language after real bugs exposed how
badly "just don't have data races" needed to be specified precisely.

Java:
- Java 1.0–1.4 (1996–2002): `volatile` and `synchronized` existed,
  but the original JLS memory model (chapter 17) was vague enough
  that legal compiler optimizations could produce results no
  programmer would consider sane - famously, a correctly-synchronized
  program could still observe a `final` field change value after
  construction, and double-checked locking (the naive form) was
  "supposed" to work but didn't, on real JVMs, under real reordering.
- JSR-133 / Java 5 (2004): a full rewrite of the JMM, formalizing
  happens-before, fixing `volatile` to have acquire/release semantics
  (it previously only prevented reordering among volatiles, not
  between volatile and non-volatile accesses), and giving `final`
  fields their safe-publication guarantee. This is the JMM as
  described throughout this README.
- Java 8 (2014): `StampedLock` added - see the deep dive below.
- Java 9 (2017, JEP 193): `VarHandle` brought an explicit
  acquire/release/opaque/plain access-mode dial to the public API for
  the first time - see the Fences deep dive below.

C++:
- C++98/03: no concept of threads in the standard at all. Anything
  involving multiple threads was purely a platform extension (POSIX
  threads, Win32 threads) with no language-level memory model to
  reason about - the compiler was, technically, allowed to assume
  single-threaded execution when optimizing.
- C++11 (2011): the watershed release. `<thread>`, `<mutex>`,
  `<condition_variable>`, and `<atomic>` all arrived together, along
  with a real memory model (largely based on the Java Memory Model's
  happens-before concept, plus the explicit `memory_order` enum) - the
  first time "what a data race even means" had a normative answer in
  the C++ standard.
- C++14: minor atomics refinements; `std::shared_timed_mutex`.
- C++17: `std::shared_mutex` (the non-timed, cheaper version used
  throughout this project) and `std::scoped_lock` for locking multiple
  mutexes atomically.
- C++20: `std::atomic_flag::test()` (used in `spin_lock.hpp`),
  `std::latch`, `std::barrier`, `std::counting_semaphore`, `std::jthread`
  (a `std::thread` that auto-joins and supports cooperative
  cancellation), and `std::atomic<T>::wait/notify` (futex-like blocking
  on an atomic, without a separate condition variable).

The short version: Java got a rigorous memory model 20 years into the
language's life because production bugs demanded one; C++ got one at
the same moment it got threads at all, by importing the lesson Java
had already learned the hard way.

---

## Double-checked locking - broken in one, fine in the other, for interesting reasons

Both languages have the same naive bug if you write DCL with an
ordinary field/pointer: a thread can observe a non-null singleton
reference before the constructor's writes are visible to it, because
nothing establishes a happens-before/ordering edge between "construct
the object" and "publish the pointer."

Where they diverge is in how each language lets you fix it - and how
necessary the fix even is.

### Java: the fix is one keyword, and it's fully portable
```java
private static volatile Helper helper; // <-- add this word, done
```
Since JSR-133 (Java 5, 2004), `volatile` gives the field release
semantics on write and acquire semantics on read. That is the entire
fix - no explicit memory-order arguments, no platform-specific
reasoning. This is why "broken DCL" is mostly a historical footnote in
Java: it was broken before Java 5 (`volatile` didn't have these
semantics yet), and has had one clean, standard, always-correct fix for
20+ years. See `BrokenSingleton`, `FixedSingleton`, and
`HolderSingleton` (the classloading-based alternative that avoids DCL
altogether) in `DoubleCheckedLocking.java`.

### C++: the naive version isn't just "wrong," it's undefined behavior - and pre-C++11 there was no fix at all
Before C++11, the language had no memory model for threads
whatsoever (the standard didn't even acknowledge threads existed) - so
there was no way to express "this pointer publish is safe" in
standard C++, full stop. This is precisely the subject of Meyers &
Alexandrescu's well-known 2004 paper "C++ and the Perils of
Double-Checked Locking," which argued the pattern was fundamentally
unfixable in the C++ of that era on real hardware.

Since C++11, you can fix it correctly, but the fix requires
understanding acquire/release explicitly:
```cpp
std::atomic<Helper*> instance{nullptr};
// acquire on the fast-path read, release on the publishing write
```
See `broken`, `fixed`, and `magic_static` namespaces in
`double_checked_locking.cpp`.

The "fine for interesting reasons" part: modern C++ also gives you
an escape hatch Java doesn't have built into the language the same
way - a function-local `static` variable is guaranteed by the
standard to be initialized exactly once, thread-safely, even under a
concurrent first call (informally "magic statics"):
```cpp
Helper& getInstance() {
    static Helper instance(42); // compiler emits the double-checked
                                // locking (or equivalent) FOR you
    return instance;
}
```
This makes manual DCL for the ordinary singleton case obsolete in
C++11+ - the compiler does the tricky part correctly, behind the
scenes, so there's rarely a reason to hand-write it at all. Java's
closest equivalent is the Holder idiom (a nested static class,
relying on classloading being thread-safe and lazy) - conceptually
similar, but syntactically it's still a pattern you have to know and
apply yourself, whereas in C++ it's just... a local variable.

Summary: naive DCL is broken in both languages without the right
primitive. Java's fix is a single, always-correct keyword thanks to a
20-year-old language guarantee. C++'s fix requires you to reason about
explicit memory orders - but C++ also lets you sidestep the whole
pattern via magic statics, in a way Java has no direct syntactic
equivalent for.

---

## Negative tests: catching broken code, in both languages, two different ways

Every test suite so far asserts a positive property: correct code
produces the right answer. Both languages also have a negative test
pair here - over code that's supposed to be broken - but they get
there in genuinely different ways, and comparing those two ways is the
actual point of this section.

Why you can't just assert your way to this in either language. A
data race on plain (non-atomic, non-volatile) memory has no
well-defined wrong value to check for. In C++ it's undefined behavior:
it might print the right answer every time on your machine, or the
wrong one, or (as we found while building this) hang forever, because
the compiler is entitled to assume no other thread touches an
unsynchronized variable and hoist a `while (!ready) {}` read right out
of the loop. In Java it's not undefined behavior - but as we found
while building the Java negative tests, "not UB" doesn't mean "can't
hang": HotSpot's C2 JIT is entitled to make the exact same
loop-invariant assumption about a non-`volatile` field and hoist the
read, producing a real, reproducible hang despite the JMM never
licensing anything as unbounded as C++'s UB. Either way, "it passed my
test" proves nothing about code whose bug is unpredictability.

### C++: deterministic detection via ThreadSanitizer

`cpp/tests/unsafe_publication_race.cpp` and
`cpp/tests/broken_singleton_race.cpp` are intentionally buggy -
broken twins of `volatile_publication.cpp` and
`double_checked_locking.cpp`'s `broken` namespace, with the atomics
replaced by plain fields. Both are compiled with GCC/Clang's
ThreadSanitizer (`-fsanitize=thread`), which instruments every
memory access at compile time and reports the exact conflicting
read/write pair, deterministically, the moment the racing code path is
exercised - no need to get lucky on timing to see the bug, only to
hit the path at all:
```bash
make test-cpp-negative
```
These are opt-in (off by default) because they need a sanitizer-capable
compiler and add real runtime overhead. Pass/fail is deliberately
inverted: each is registered with CTest's `PASS_REGULAR_EXPRESSION`
matching `"WARNING: ThreadSanitizer: data race"` - a PASS means the
tool caught the bug, regardless of the process's own exit code. We
verified the converse too: pointing the same harness at the fixed
(`std::atomic`) versions produces no warning and exits cleanly,
confirming these tests would actually catch a regression rather than
passing unconditionally.

### Java: empirical reproduction, with a genuinely honest asymmetry

`java/tests/UnsafePublicationRaceTest.java` and
`java/tests/BrokenSingletonRaceTest.java` are the same idea - broken
twins of the safe-publication and DCL-singleton patterns - but Java has
no ThreadSanitizer-equivalent, so there's no instrumentation to catch
the access pattern independent of whether the bad reordering actually
happens on a given run. All either test can do is run the racy code
under stress and report what it observed. We built both, ran them
honestly, and got two different answers:

- `UnsafePublicationRaceTest` reproduces reliably. On OpenJDK 21
  HotSpot with default settings, the unsynchronized `while (!ready) {}`
  consumer loop hangs - every time, in our testing - because C2
  compiles the loop, sees no synchronization on `ready`, and hoists the
  read out entirely. This test asserts the bug manifests (a timeout or
  a wrong observed value) within a handful of bounded-timeout rounds,
  and it does.
- `BrokenSingletonRaceTest` does not reproduce reliably. Across
  800,000 observations (50,000 rounds × 4 threads) hunting for a
  partially-constructed `Helper` object, we observed zero
  inconsistencies on x86-64. Not because the code is safe - it's
  exactly as broken as `BrokenSingleton` in
  `DoubleCheckedLocking.java` - but because x86's strong hardware
  memory ordering ([TSO][x86-tso]) rarely lets stores reorder relative
  to each other in a way that exposes this specific bug, even though
  the JLS permits it. This test therefore does not assert an anomaly must
  occur - doing so would make the suite flaky for the wrong reason. It
  only asserts the stress run completes without crashing, and reports
  the anomaly count as information, with an explicit note that zero
  observed here proves nothing about correctness.

Both run automatically as part of the regular suite - no opt-in, no
special compiler flag, because Java has no sanitizer-style toolchain
requirement to gate behind in the first place:
```bash
make test-java   # includes both negative tests, every time
```

### The asymmetry, stated plainly

This is the actual finding, not just a caveat: C++'s negative tests
prove something regardless of what happens to occur on a given run
(ThreadSanitizer flags the unsynchronized access pattern itself, not a
particular bad outcome). Java's negative tests can only report what
was observed - and what's observed depends on the JIT, the CPU
architecture, and luck. One of the two Java bugs happened to reproduce
reliably here (a compiler-level effect, not a hardware one); the other
didn't (it needs a hardware reordering that's rare on x86 specifically,
though it could very plausibly show up on ARM or with a different JIT).
Neither outcome tells you the code is safe - only ThreadSanitizer's
report tells you anything with certainty, and only for the language
that has it. The nearest thing Java has to a real answer here is
[jcstress](https://github.com/openjdk/jcstress), OpenJDK's own
concurrency-stress-testing harness - but it works differently again:
rather than instrumenting arbitrary code, you write specific
interleaving-sensitive test cases and jcstress runs them under heavy
scheduling pressure (forking many JVMs, pinning threads to cores) to
enumerate which outcomes actually occur and flag "forbidden" ones you
declare up front. It's a heavier, different tool than a compiler flag,
and it isn't included in this project for the same reason the rest of
this repo avoids external dependencies - but it's the right thing to
reach for if you need a stronger guarantee than "we tried really hard
and didn't see it" on real Java production code.

---

## A lock-free SPSC ring buffer

`SpscRingBuffer.java` and `include/spsc_ring_buffer.hpp` implement the
same algorithm (in the style of the LMAX Disruptor): a fixed-capacity
circular array with two indices, `head` (owned by the consumer) and
`tail` (owned by the producer). No mutex, no CAS even - because with
exactly one producer and one consumer, there is never a write/write
race on either index, only the classic "publish a value, then flip a
flag" pattern applied per-slot:

1. Producer writes the slot's data.
2. Producer publishes by advancing `tail` with a release store.
3. Consumer checks for new data by loading `tail` with an acquire load.
4. Because of the release/acquire pairing, if the consumer sees the
   new `tail`, it is guaranteed to also see the slot data written in
   step 1 - the identical guarantee as the two-thread publish/subscribe
   example in `volatile_publication.cpp` / `VolatilePublication.java`,
   just applied to every element instead of once.

### Where the two implementations genuinely differ

- Java's `lazySet` (`AtomicLong.lazySet`) is a release-only store -
  cheaper than a full volatile write because it skips the expensive
  StoreLoad fence, while still being visible to the consumer in the
  right order eventually. It's Java's one real escape hatch into
  "weaker-than-default" ordering for atomics.
- C++'s explicit `memory_order_relaxed` / `acquire` / `release`
  give the same relaxation, but per-call, and composably with any
  atomic type - not just a special method on one class.
- Cache-line padding. `spsc_ring_buffer.hpp` uses `alignas(64)` to
  put `head` and `tail` on separate cache lines. Without this, both
  indices can land on the same cache line, and every producer write
  to `tail` invalidates that line in the consumer's cache even though
  `head` and `tail` are logically unrelated - a performance bug called
  false sharing. C++ gives you direct, standard language control
  over this (`alignas`). Java has no public, portable equivalent; the
  closest is the internal, JDK-only `@Contended` annotation, which
  isn't part of the public API contract.

Running both demos (`SpscRingBuffer.java`'s `main`, and
`spsc_ring_buffer_demo.cpp`) pushes/pops 5,000,000 integers between a
producer and consumer thread and prints a checksum plus elapsed time.
Expect the C++ version to be dramatically faster in absolute terms -
but treat that gap with a grain of salt: the Java number includes JVM
startup and JIT warm-up inside the timed region, and both demos use a
busy-spin retry loop rather than a more realistic backoff strategy.
The point of the exercise is the algorithm and ordering, not a
rigorous benchmark.

---

## Fences - ordering without an attached variable

Every example so far attaches ordering to a specific memory access: an
acquire load, a release store. Both languages also let you issue a
standalone fence - an instruction that orders "everything around
this point in program order" without being tied to any one variable.

Java (`VarHandle`, since Java 9 / JEP 193):
```java
VarHandle.releaseFence();  // nothing above this line can be reordered below it
VarHandle.acquireFence();  // nothing below this line can be reordered above it
VarHandle.fullFence();     // both, plus a global order (the seq_cst analog)
```

C++ (`std::atomic_thread_fence`, since C++11):
```cpp
std::atomic_thread_fence(std::memory_order_release);
std::atomic_thread_fence(std::memory_order_acquire);
std::atomic_thread_fence(std::memory_order_seq_cst);
```

See `FencesDemo.java` / `fences_demo.cpp`: both publish two plain
(Java) or relaxed-atomic (C++) values through a single flag, using one
release fence on the producer side and one matching acquire fence on
the consumer side, instead of putting acquire/release semantics on
every individual value. That's the actual use case for a fence over
per-operation ordering: when several accesses need to be ordered
together relative to one publish point, a single fence is cheaper and
clearer than decorating each access individually.

Two things worth knowing:
- Java's `VarHandle` also has finer access modes than plain vs
  volatile - `getOpaque`/`setOpaque` (≈ `memory_order_relaxed`) and
  `getAcquire`/`setRelease` (≈ `memory_order_acquire`/`_release`)
  attached directly to a specific field, not just standalone fences.
  Before Java 9, none of this was public API - you got `volatile`
  (always the strongest ordering) or nothing.
- C++ additionally has `std::atomic_signal_fence`, which orders
  accesses only against a signal handler running on the same
  thread - a compiler-only fence with no CPU instruction emitted at
  all, since a signal handler can't run concurrently with the thread
  it interrupts, only interleaved with it. Java has no direct
  equivalent, since it has no analog of asynchronous signal handlers
  running on the same OS thread.

---

## Spin locks - when busy-waiting beats blocking

A spinlock trades "block the thread and let the OS scheduler hand the
core to someone else" for "keep the thread runnable and just poll in a
tight loop until the lock frees up." That's a good trade only when the
critical section is extremely short (a handful of instructions) and
there are at least as many CPU cores as contending threads - otherwise
you're burning cycles a blocking lock would have handed to useful
work, and can even starve the very thread holding the lock if the
scheduler preempts it while a spinner keeps its core busy.

Both `SpinLock.java` and `spin_lock.hpp` implement the same
test-and-test-and-set (TTAS) pattern rather than naive
test-and-set:

```java
// Java (AtomicBoolean)
while (true) {
    while (locked.get()) Thread.onSpinWait();       // TEST: cheap plain read
    if (locked.compareAndSet(false, true)) return;   // ...and only then SET via CAS
}
```
```cpp
// C++ (std::atomic_flag)
while (true) {
    while (flag.test(std::memory_order_relaxed))     // TEST: cheap relaxed read
        std::this_thread::yield();
    if (!flag.test_and_set(std::memory_order_acquire)) // ...and only then SET via RMW
        return;
}
```

The "test" half matters for contended performance: a CAS/`test_and_set`
is a read-modify-write, which invalidates the cache line on every other
core caching it - even when it fails. Spinning on a plain read instead
lets the cache line stay shared (cheap to poll) right up until it
actually looks free, and only then attempts the one RMW that might
succeed. This is the standard first optimization in every real-world
spinlock implementation, in both languages.

`std::atomic_flag` is notable in its own right: it's the only
`std::atomic` specialization the standard guarantees is always
lock-free on every conforming platform (every other `std::atomic<T>`
is merely usually lock-free, checked at runtime via `is_lock_free()`).
Java's `AtomicBoolean` carries no such formal guarantee, though in
practice every mainstream JVM implements it without an OS-level lock.

---

## StampedLock (Java) vs a hand-rolled Seqlock (C++)

`ReentrantReadWriteLock`/`std::shared_mutex` (section 4 of the file
table below) let many readers in at once, but every reader still takes
some lock, which means readers still contend with each other over
that lock's internal state. Optimistic reads go one step further:
a reader takes no lock at all, and only pays a cost if it turns out a
writer actually interfered.

Java's `StampedLock` (Java 8+) builds this in as a standard,
three-mode lock - see `StampedLockDemo.java`:
```java
long stamp = sl.tryOptimisticRead();   // no lock taken at all
double x = this.x, y = this.y;          // plain reads -- may race with a writer
if (!sl.validate(stamp)) {              // did a writer interfere?
    stamp = sl.readLock();              // yes: fall back to a real (blocking) read lock
    try { x = this.x; y = this.y; } finally { sl.unlockRead(stamp); }
}
```

C++ has no standard-library equivalent. The pattern you hand-roll
instead is the classic seqlock - see `seqlock.hpp`:
```cpp
uint64_t s1 = seq.load(acquire);
if (s1 & 1) continue;      // writer mid-flight -- don't even bother reading
T snapshot = data;           // may race with a writer -- checked below
uint64_t s2 = seq.load(acquire);
if (s1 == s2) return snapshot;  // consistent; otherwise retry
```
Same idea, spelled out by hand: an odd/even sequence counter instead
of `StampedLock`'s internal version stamp, a plain retry loop instead
of `validate()` triggering a fallback path. `StampedLock` is, in a
real sense, "seqlock, standardized and shipped in the JDK" - and it's
worth knowing this is a widely-used real-world pattern (the Linux
kernel uses essentially this same technique for things like the
timekeeping subsystem) rather than something exotic.

What both tests in this project actually verify (`StampedLockTest.java`,
`seqlock_test.cpp`): a `Point{x, y}` where a writer always updates `x`
and `y` together to the same value, so `x == y` is an invariant that
must hold in any consistent snapshot. The writer updates the two
fields in two separate, non-atomic steps - deliberately, so an
improperly-guarded reader could catch `x` updated but not yet `y` (a
torn read). Both tests hammer a concurrent reader against a concurrent
writer and assert the invariant is never violated across hundreds of
thousands of reads - which is exactly the property optimistic
reads/seqlocks exist to guarantee without ever blocking a reader
against a writer.

One honest caveat, noted in `seqlock.hpp`'s comments: whether the
classic seqlock pattern (plain acquire/release on the sequence
counter) is strictly airtight under the formal C++ abstract machine,
for arbitrary present-and-future compiler optimizations, has been an
active topic of discussion among the C++ standards committee - it is
unambiguously correct in practice on real compilers and hardware
today, and is exactly how it's conventionally written, but "provably
correct against the abstract machine" and "correct on every compiler
you'll actually use" aren't quite the same claim. `StampedLock` doesn't
have this concern: it's implemented and specified by the JDK itself,
not hand-assembled from lower-level primitives by application code.

---

## The rest of the comparison, file by file

| Topic | Java | C++ | What to notice |
|---|---|---|---|
| Safe publication | `VolatilePublication.java` | `volatile_publication.cpp` | Same guarantee; C++'s `volatile` keyword is a red herring - use `std::atomic` |
| CAS | `CasCounter.java` | `cas_counter.cpp` | Java's atomics are always seq-cst-like; C++ lets you drop to `relaxed` for a bare counter |
| Read-write lock | `RwLockCache.java` | `rw_lock_cache.cpp` | C++'s RAII guards (`shared_lock`/`unique_lock`) unlock automatically, even on exceptions - no `try/finally` needed |
| Blocking queue | `BlockingQueueDemo.java` | `blocking_queue_demo.cpp` | Java: pick a class off the shelf. C++: build it from `mutex` + `condition_variable`, or take a dependency |
| Classic blocking queue (hand-rolled) | `ClassicBlockingQueue.java` | `blocking_queue.hpp` / `blocking_queue_demo.cpp` | See Section 10 below |
| CAS-based MPMC queue | `CasMpmcQueue.java` | `cas_mpmc_queue.hpp` / `_demo.cpp` | See Section 10 below |
| Double-checked locking | `DoubleCheckedLocking.java` | `double_checked_locking.cpp` | See deep dive above |
| Lock-free SPSC queue | `SpscRingBuffer.java` | `spsc_ring_buffer.hpp` / `_demo.cpp` | See deep dive above |
| Fences | `FencesDemo.java` | `fences_demo.cpp` | See deep dive above |
| Spin lock | `SpinLock.java` | `spin_lock.hpp` / `_demo.cpp` | See deep dive above |
| Optimistic read | `StampedLockDemo.java` | `seqlock.hpp` / `_demo.cpp` | See deep dive above |
| Negative tests | `UnsafePublicationRaceTest.java`, `BrokenSingletonRaceTest.java` (empirical) | `tests/unsafe_publication_race.cpp`, `tests/broken_singleton_race.cpp` (ThreadSanitizer, deterministic) | See Section 3 above |

---

## SPSC vs MPMC, and what "lock-free" actually costs you

Section 4 covered `SpscRingBuffer` - lock-free, but not CAS-based,
because with exactly one producer and one consumer there's never a
write/write race on either index to resolve. This section covers the
two queues that fill in the rest of the design space: `SpscRingBuffer`
generalized to any number of producers/consumers, done two different
ways.

- `ClassicBlockingQueue.java` / `blocking_queue.hpp` - the queue
  from Section 8's "Blocking queue" row, but with the locking made
  explicit rather than hidden behind `ArrayBlockingQueue`. One mutex
  (Java: `ReentrantLock`), two condition variables - `notFull` wakes a
  waiting producer, `notEmpty` wakes a waiting consumer. A thread that
  finds the queue full/empty actually blocks: the OS descheduled
  it, it's not spending any CPU, and it wakes only when signaled.
- `CasMpmcQueue.java` / `cas_mpmc_queue.hpp` - [Dmitry Vyukov's
  bounded MPMC ring buffer][vyukov-mpmc]. No lock, no blocked thread,
  ever. Every slot carries its own `sequence` counter; a producer or
  consumer claims a slot with a CAS on the shared index, and if it
  loses the race, the CAS just fails and it retries against the
  freshly-read index. See Section 10 for why multiple producers make CAS
  necessary here in a way it wasn't for the SPSC buffer.

Both pass the same MPMC stress test (4 producers, 4 consumers, checksum
over every item, in `ClassicBlockingQueueTest`/`CasMpmcQueueTest` and
their C++ twins) - same correctness guarantee, two very different
mechanisms for getting there. Section 10 benchmarks them against each
other and unpacks what the numbers actually mean.

## What CAS actually buys you - benchmarking a lock-free queue against a classic one

This is the direct comparison: `QueueBenchmark.java` / `queue_benchmark.cpp`
run the same total item count through both queues, at four
producer/consumer counts (1v1, 2v2, 4v4, 8v8), timing each run. Both
languages' scripts share the exact same shape: a shared `AtomicLong`/
`std::atomic<long>` counter hands out item indices to producers, and
consumers stop on a poison pill (`ClassicBlockingQueue`/`BlockingQueue`)
or a shared "consumed so far" counter (`CasMpmcQueue`, safe here only
because `poll()` never blocks - see the callout below).

### What CAS actually is, and why the SPSC buffer didn't need it

Every offer/poll in `CasMpmcQueue` boils down to one line:
```java
if (enqueuePos.compareAndSet(pos, pos + 1)) { break; } // else: retry
```
"Compare-and-swap" means: atomically, check whether the memory still
holds the value I last saw (`pos`); if so, write the new value
(`pos + 1`) and report success; if not, change nothing and report
failure, so the caller can re-read and retry. That single
hardware-backed atomic read-modify-write is the entire mechanism -
there's no lock object, no OS involvement, no thread ever sleeps.

`SpscRingBuffer` (Section 4) never needs this: with exactly one
producer, only that one thread ever writes `tail`, so there is no
write/write race to arbitrate - a plain release-store is already
enough. The moment you allow a second producer, two threads can both
try to claim the same slot at the same instant, and something has to
be the tiebreaker. CAS is that tiebreaker: it's the only way to say
"advance this index, but only if nobody beat me to it" without taking
a lock first.

### The results (this sandbox, single core)

```
producers  consumers  classic (lock+condvar)   CAS (lock-free)
1          1          308 ms (6493 items/ms)   13406 ms (149 items/ms)
2          2          227 ms (8810 items/ms)   17652 ms (113 items/ms)
4          4          142 ms (14084 items/ms)  20815 ms (96 items/ms)
8          8          143 ms (13986 items/ms)  24919 ms (80 items/ms)
```
(Java; 2,000,000 items per run.) The C++ numbers, on the exact same
single-core machine, tell the opposite story:
```
producers  consumers  classic (lock+condvar)   CAS (lock-free)
1          1          211 ms (9478 items/ms)   62 ms (32258 items/ms)
2          2          164 ms (12195 items/ms)  66 ms (30303 items/ms)
4          4          170 ms (11764 items/ms)  71 ms (28169 items/ms)
8          8          205 ms (9756 items/ms)   85 ms (23529 items/ms)
```
Taken at face value, these two tables disagree about whether CAS is
even worth it. They aren't actually measuring the same thing, and
figuring out why is the real lesson here.

### Why: this sandbox has exactly one CPU core

`nproc` reports `1` in the environment this was built and run in. That
single fact dominates both tables, but in opposite directions,
because of one small difference between the two languages' retry
loops:

- `CasMpmcQueue.java`'s retry loop calls `Thread.onSpinWait()` - the
  JDK 9+ idiom for a busy-wait (it compiles to the x86 `PAUSE`
  instruction). This is a hint to the core, not the scheduler: it
  does not give up the thread's turn. On real multi-core hardware
  that's exactly right - the point of spinning is to keep the thread
  runnable so it can grab the lock/slot the instant it's free, without
  paying a context-switch's cost. On a single core, though, it means a
  spinning thread burns its entire scheduling quantum doing
  nothing useful, while the producer or consumer that could actually
  make progress sits waiting for the timer interrupt that finally
  preempts the spinner. With 8 producers + 8 consumers all doing this
  at once, on the one core available, the result is the 25-second run
  above - CAS "loses" not because compare-and-swap is slow, but
  because busy-waiting without yielding is a genuinely bad idea when
  there's nowhere else for the CPU to be.
- `cas_mpmc_queue_demo.cpp` / `queue_benchmark.cpp`'s retry loop calls
  `std::this_thread::yield()` instead - which does ask the scheduler
  to hand the core to someone else right now, rather than waiting for
  a preemption. That's why the C++ numbers don't fall off a cliff:
  every failed CAS attempt promptly gives another thread a turn.

To confirm this was really the cause and not some other Java/C++
difference, swapping Java's `Thread.onSpinWait()` for `Thread.yield()`
in an otherwise-identical copy of `QueueBenchmark` was enough on its
own to flip the result - the CAS queue went from ~25x slower than the
classic queue to consistently faster than it, on the same single
core:
```
producers  consumers  classic (lock+condvar)   CAS (lock-free), Thread.yield()
1          1          285 ms (7017 items/ms)   154 ms (12987 items/ms)
2          2          240 ms (8333 items/ms)   157 ms (12738 items/ms)
4          4          133 ms (15037 items/ms)  109 ms (18348 items/ms)
8          8          159 ms (12578 items/ms)  112 ms (17857 items/ms)
```
This isn't shipped as the real implementation, on purpose:
`Thread.onSpinWait()` is the correct choice on genuine multi-core
hardware - it's what `SpscRingBuffer.java` and `SpinLock.java` already
use elsewhere in this project, for good reason (see Section 6). Using
`Thread.yield()` there would trade away real-world performance just to
look better on this one constrained sandbox. The numbers above exist
to show why the sandbox's CAS results look the way they do, not to
argue Java's spin idiom is wrong.

### So what does CAS actually buy you?

Strip away the single-core artifact above, and the real, general
tradeoffs are these:

#### In CAS's favor
- No thread ever blocks. A lock-based queue that's full or empty
  puts the calling thread to sleep and relies on the OS to wake it -
  that's a real syscall (`futex_wait`/`park`) on the way in and
  another (`futex_wake`/`unpark`) on the way out, plus scheduling
  latency before the woken thread actually runs again. A CAS retry
  loop pays none of that: on real multi-core hardware, a producer that
  loses a race simply reads the updated index (already sitting in
  cache, usually) and tries again, often within nanoseconds.
- No priority inversion, no lock convoy. A thread holding a mutex
  that gets preempted (or, worse, deprioritized) can stall every other
  thread waiting on that same lock. There's no analogous failure mode
  for a CAS loop - there's no "holder" to be delayed in the first
  place.
- System-wide progress is guaranteed. Every failed CAS in this
  algorithm is caused by some other thread's CAS having just
  succeeded - so on every failed attempt, the system as a whole made
  progress, even if this particular thread didn't. (This is the formal
  property called lock-freedom: contrast with a mutex, where a
  descheduled lock-holder can stall everyone until the scheduler gets
  back to it.)

#### In the classic queue's favor
- No wasted CPU under real contention. A blocked thread costs
  (almost) nothing while it waits. A spinning thread costs a full core
  the entire time it's retrying - genuinely wasteful if the wait is
  long, and actively harmful if there are more runnable threads than
  cores, exactly as this sandbox's single-core numbers show.
- Simpler to reason about and far easier to get right. `offer()`/
  `poll()` above needed a hand-derived per-slot sequence-number scheme
  just to be correct; `ClassicBlockingQueue.put()`/`take()` is eight
  lines built from two textbook primitives every working programmer
  already knows. The ABA problem, memory reclamation for unbounded
  lock-free structures, and subtle ordering bugs are all real hazards
  in lock-free code that a lock-based design sidesteps by construction.
- Backpressure comes for free. A full `ClassicBlockingQueue` makes
  producers wait - which is often exactly the throttling behavior
  you want. `CasMpmcQueue.offer()` just returns `false` immediately;
  building the same backpressure on top of that means writing your own
  spin/backoff/park loop, which is precisely the kind of code this
  section is warning you is easy to get subtly wrong.

The honest summary: CAS wins when contention is real (multiple
cores genuinely racing) and critical sections are tiny - exactly the
same condition under which `SpinLock` (Section 6) beats a blocking
mutex, for the same underlying reason. It loses, sometimes badly, the
moment there are more spinning threads than cores to run them, which
is precisely what this sandbox's single core forced into the open. On
real multi-core hardware, expect `CasMpmcQueue` to pull ahead of
`ClassicBlockingQueue` as contention rises - but reproduce the
benchmark on your own multi-core machine before trusting that over
what's printed above; `nproc` here really does say `1`, and that fact
alone explains the Java table completely.

## One-paragraph takeaway

Java's memory model hides most hardware-level complexity behind a
small set of strong, always-on primitives (`volatile`, `synchronized`,
`java.util.concurrent`) and never lets a data race become undefined
behavior - safer defaults, less tuning room, and a rich standard
library of ready-made concurrent collections. Modern C++ exposes the
actual ordering knobs (`relaxed` → `acquire`/`release` → `seq_cst`),
gives you direct control over memory layout (cache-line padding), and
offers a genuinely elegant escape hatch for lazy initialization (magic
statics) - at the cost of data races being true undefined behavior,
and most concurrent data structures beyond a mutex/atomic having to be
hand-built or imported rather than picked off a shelf.

## References

Primary sources for the claims made throughout this README, in case
you want to go past "trust me" on any of them.

Java Memory Model
- Jeremy Manson and Brian Goetz, [JSR-133 (Java Memory Model) FAQ][jsr133-faq] - the accessible explanation of happens-before, referenced in Section 1
- Doug Lea, [The JSR-133 Cookbook for Compiler Writers][jsr133-cookbook] - the same material aimed at implementers, with the actual barrier/reordering tables
- [Java Language Specification, Chapter 17: Threads and Locks][jls-17] - the normative text JSR-133 formalized

Modern C++ memory model
- [`std::memory_order` - cppreference][cppref-memory-order] - the relaxed/acquire/release/seq_cst reference used throughout Sections 1, 8, and 10
- Herb Sutter, ["atomic<> Weapons" (C++ and Beyond, 2012)][sutter-atomic-weapons] - the standard deep-dive talk on why the ordering model looks the way it does
- [POSIX `pthread_mutex_unlock` specification][posix-mutex] - the `PTHREAD_MUTEX_ERRORCHECK` behavior cited in Section 0

Hardware memory model
- Owens, Sarkar, and Sewell, [x86-TSO: A Rigorous and Usable Programmer's Model for x86 Multiprocessors][x86-tso] - the formal model behind Section 3's "why the Java negative test doesn't reproduce on x86" finding

Lock-free queues and CAS
- Dmitry Vyukov, [Bounded MPMC queue][vyukov-mpmc] - the algorithm `CasMpmcQueue.java`/`cas_mpmc_queue.hpp` implement, described in Sections 9 and 10. Vyukov's own notes there are worth reading directly: he's explicit that this is "not lock-free in the official meaning, just implemented by means of atomic RMW operations w/o mutexes" - a precision this README elides for readability, but worth knowing if you go implement one yourself
- Maged Michael and Michael Scott, ["Simple, Fast, and Practical Non-Blocking and Blocking Concurrent Queue Algorithms"][michael-scott] (PODC 1996) - the original unbounded lock-free MPMC queue paper; a different design from Vyukov's bounded ring buffer, but the standard reference point for the field
- Maurice Herlihy and Nir Shavit, The Art of Multiprocessor Programming - the textbook treatment of lock-freedom, linearizability, and the ABA problem referenced in Section 10's tradeoffs discussion

Java concurrency library documentation
- [`java.util.concurrent.locks.Condition` - Javadoc][javadoc-condition] - the `ClassicBlockingQueue.java` primitive
- [`java.util.concurrent.locks.StampedLock` - Javadoc][javadoc-stampedlock] - the Section 7 deep dive

[jsr133-faq]: https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html
[jsr133-cookbook]: https://gee.cs.oswego.edu/dl/jmm/cookbook.html
[jls-17]: https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html
[cppref-memory-order]: https://en.cppreference.com/w/cpp/atomic/memory_order
[sutter-atomic-weapons]: https://herbsutter.com/2013/02/11/atomic-weapons-the-c-memory-model-and-modern-hardware/
[posix-mutex]: https://pubs.opengroup.org/onlinepubs/9699919799/functions/pthread_mutex_unlock.html
[x86-tso]: https://www.cl.cam.ac.uk/~pes20/weakmemory/cacm.pdf
[vyukov-mpmc]: http://www.1024cores.net/home/lock-free-algorithms/queues/bounded-mpmc-queue
[michael-scott]: https://www.cs.rochester.edu/~scott/papers/1996_PODC_queues.pdf
[javadoc-condition]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/Condition.html
[javadoc-stampedlock]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/StampedLock.html
