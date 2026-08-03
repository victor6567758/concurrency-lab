cmake_minimum_required(VERSION 3.16)
project(concurrency_lab_cpp CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

find_package(Threads REQUIRED)
include_directories(include)

set(DEMOS
    mutex_ownership_demo
    volatile_publication
    cas_counter
    double_checked_locking
    rw_lock_cache
    blocking_queue_demo
    spsc_ring_buffer_demo
    spin_lock_demo
    seqlock_demo
    fences_demo
    cas_mpmc_queue_demo
    queue_benchmark
)

foreach(demo ${DEMOS})
    add_executable(${demo} src/${demo}.cpp)
    target_link_libraries(${demo} PRIVATE Threads::Threads)
endforeach()

# --- Tests ---------------------------------------------------------------
enable_testing()

set(TESTS
    cas_counter_test
    spsc_ring_buffer_test
    dcl_singletons_test
    cache_test
    blocking_queue_test
    spin_lock_test
    seqlock_test
    fences_test
    volatile_publication_test
    mutex_ownership_test
    cas_mpmc_queue_test
)

foreach(test_name ${TESTS})
    add_executable(${test_name} tests/${test_name}.cpp)
    target_include_directories(${test_name} PRIVATE tests)
    target_link_libraries(${test_name} PRIVATE Threads::Threads)
    add_test(NAME ${test_name} COMMAND ${test_name})
endforeach()

# --- Negative tests: intentionally-buggy code, caught by ThreadSanitizer --
# Source files live in tests/ alongside the regular suites (they're
# just compiled differently, not organized differently). OFF by
# default: these need a sanitizer-capable compiler (GCC/Clang on
# Linux/macOS; not available at all on MSVC) and are meaningfully
# slower to run than the rest of the suite. Enable explicitly with:
#   cmake .. -DENABLE_TSAN_NEGATIVE_TESTS=ON
option(ENABLE_TSAN_NEGATIVE_TESTS
       "Build the negative tests under ThreadSanitizer (needs GCC/Clang)" OFF)

if(ENABLE_TSAN_NEGATIVE_TESTS)
    set(NEGATIVE_TESTS
        unsafe_publication_race
        broken_singleton_race
    )

    foreach(neg_test ${NEGATIVE_TESTS})
        add_executable(${neg_test} tests/${neg_test}.cpp)
        target_compile_options(${neg_test} PRIVATE -fsanitize=thread -g -O1)
        target_link_options(${neg_test} PRIVATE -fsanitize=thread)
        target_link_libraries(${neg_test} PRIVATE Threads::Threads)

        # Inverted pass/fail semantics, on purpose: this test's CODE is
        # a known bug. It "passes" only when ThreadSanitizer's report
        # is present in the output -- i.e., when the tool catches the
        # exact race the file exists to demonstrate. See the README's
        # "Negative tests" section before changing this.
        add_test(NAME ${neg_test} COMMAND ${neg_test})
        set_tests_properties(${neg_test} PROPERTIES
            PASS_REGULAR_EXPRESSION "WARNING: ThreadSanitizer: data race"
            TIMEOUT 60
        )
    endforeach()
endif()
