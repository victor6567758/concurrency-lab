find_package(Threads REQUIRED)

# --- IDE-facing target: every source/header belongs here --------------
# CLion (and clangd) only index files that are listed as SOURCES of at
# least one target. Headers pulled in via #include and .cpp files
# behind a CMake if() guard are invisible to the IDE by default, which
# is why it shows "file doesn't belong to any target" for them.
#
# add_custom_target with SOURCES is the documented workaround: it
# creates a no-op target (nothing is built, nothing is linked) whose
# only job is to make every file in the project visible to IDE
# tooling — go-to-definition, refactoring, error highlighting, etc.
add_custom_target(lab_ide_sources SOURCES
    # Headers
    include/blocking_queue.hpp
    include/cache.hpp
    include/cas_counter.hpp
    include/cas_mpmc_queue.hpp
    include/dcl_singletons.hpp
    include/seqlock.hpp
    include/spin_lock.hpp
    include/spsc_ring_buffer.hpp
    tests/test_harness.hpp
    # Negative-test sources (conditionally compiled below, but always
    # visible to the IDE via this target)
    tests/broken_singleton_race.cpp
    tests/unsafe_publication_race.cpp
)

# Common include path for every executable below
function(lab_setup_target target_name)
    target_include_directories(${target_name} PRIVATE include)
    target_link_libraries(${target_name} PRIVATE Threads::Threads)
    add_dependencies(${target_name} lab_ide_sources)
endfunction()

# --- Demos -----------------------------------------------------------------

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
    lab_setup_target(${demo})
endforeach()

# --- Tests ---------------------------------------------------------------

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
    lab_setup_target(${test_name})
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
        lab_setup_target(${neg_test})

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