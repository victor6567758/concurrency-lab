#pragma once
// A deliberately tiny, dependency-free test harness. The sandbox this
// project was built in only has network access to package registries
// (npm, pypi, crates), not a C++ package manager or Maven Central, so
// pulling in Catch2/GoogleTest/JUnit isn't reliably reproducible for
// everyone cloning this repo either. Plain asserts + CMake/CTest cover
// everything needed here.

#include <iostream>
#include <string>

class TestHarness {
    int passed = 0;
    int failed = 0;

public:
    void check(const std::string& name, bool condition) {
        if (condition) {
            ++passed;
            std::cout << "  [PASS] " << name << "\n";
        } else {
            ++failed;
            std::cout << "  [FAIL] " << name << "\n";
        }
    }

    template <typename T, typename U>
    void checkEquals(const std::string& name, const T& expected, const U& actual) {
        bool ok = (expected == actual);
        check(name, ok);
        if (!ok) {
            std::cout << "         expected: " << expected << ", actual: " << actual << "\n";
        }
    }

    // Returns the number of failures. main() should return this (0 = success),
    // which is exactly what CTest expects from a test executable.
    int summary(const std::string& suiteName) {
        std::cout << suiteName << ": " << passed << " passed, " << failed << " failed\n";
        return failed;
    }
};
