.PHONY: all build build-java build-cpp \
        test test-java test-cpp test-cpp-negative \
        test-java-one test-cpp-one \
        run-demos-java run-demos-cpp \
        run-benchmark-java run-benchmark-cpp \
        clean help

JAVA_DIR      := java
CPP_DIR       := cpp
CPP_BUILD     := build
NPROC         := $(shell nproc 2>/dev/null || echo 2)

all: build

help:
	@echo "Targets:"
	@echo "  make build              - build Java classes and C++ binaries"
	@echo "  make build-java         - build Java classes only (mvn compile)"
	@echo "  make build-cpp          - build C++ binaries only (CMake + make)"
	@echo "  make test               - run both test suites"
	@echo "  make test-java          - run the Java test suite (mvn exec:java RunAllTests)"
	@echo "  make test-cpp           - run the C++ test suite (ctest)"
	@echo "  make test-cpp-negative  - opt-in: C++ negative tests under ThreadSanitizer"
	@echo "  make test-java-one T=X  - run a single Java test (e.g. T=CasCounterTest)"
	@echo "  make test-cpp-one T=X   - run a single C++ test (e.g. T=spin_lock_test)"
	@echo "  make run-demos-java     - run every Java demo's main() in sequence"
	@echo "  make run-demos-cpp      - run every C++ demo binary in sequence"
	@echo "  make run-benchmark-java - run QueueBenchmark (~1-2 min, not in run-demos-java)"
	@echo "  make run-benchmark-cpp  - run queue_benchmark (~1-2 min, not in run-demos-cpp)"
	@echo "  make clean              - remove all build output"

# --- Build -----------------------------------------------------------------

build: build-java build-cpp

build-java:
	@echo "==> [java] compiling via Maven"
	cd $(JAVA_DIR) && mvn -q compile

build-cpp:
	@echo "==> [cpp] configuring + building (CMake)"
	@mkdir -p $(CPP_BUILD)
	cd $(CPP_BUILD) && cmake .. -DCMAKE_BUILD_TYPE=Release
	cd $(CPP_BUILD) && $(MAKE) -j$(NPROC)

# --- Test --------------------------------------------------------------

test: test-java test-cpp

test-java:
	@echo "==> [java] running RunAllTests via Maven exec"
	cd $(JAVA_DIR) && mvn -q compile exec:java -Dexec.mainClass=RunAllTests

test-cpp:
	@echo "==> [cpp] running ctest"
	@mkdir -p $(CPP_BUILD)
	cd $(CPP_BUILD) && cmake .. -DCMAKE_BUILD_TYPE=Release
	cd $(CPP_BUILD) && $(MAKE) -j$(NPROC)
	cd $(CPP_BUILD) && ctest --output-on-failure

test-cpp-negative:
	@echo "==> [cpp] running negative tests under ThreadSanitizer (opt-in)"
	@mkdir -p $(CPP_BUILD)
	cd $(CPP_BUILD) && cmake .. -DCMAKE_BUILD_TYPE=Release -DENABLE_TSAN_NEGATIVE_TESTS=ON
	cd $(CPP_BUILD) && $(MAKE) -j$(NPROC)
	cd $(CPP_BUILD) && ctest --output-on-failure

# --- Run a single test -----------------------------------------------

test-java-one:
	@echo "==> [java] running single test: $(T)"
	@test -n "$(T)" || { echo "Usage: make test-java-one T=<TestClass>"; \
		echo "  e.g. make test-java-one T=CasCounterTest"; \
		echo "  (list: ls java/tests/*Test.java | sed 's|.*/||;s|\.java||')"; \
		exit 2; }
	cd $(JAVA_DIR) && mvn -q compile exec:java -Dexec.mainClass=$(T)

test-cpp-one:
	@echo "==> [cpp] running single test: $(T)"
	@test -n "$(T)" || { echo "Usage: make test-cpp-one T=<test_binary>"; \
		echo "  e.g. make test-cpp-one T=spin_lock_test"; \
		echo "  (list: see 'ctest -N' in $(CPP_BUILD)/ after building)"; \
		exit 2; }
	@mkdir -p $(CPP_BUILD)
	cd $(CPP_BUILD) && cmake .. -DCMAKE_BUILD_TYPE=Release
	cd $(CPP_BUILD) && $(MAKE) -j$(NPROC)
	cd $(CPP_BUILD) && ctest --output-on-failure -R '^$(T)$$'

# --- Run every demo, for a quick end-to-end sanity pass ---------------

run-demos-java: build-java
	@for demo in IllegalMonitorStateDemo VolatilePublication CasCounter \
	             DoubleCheckedLocking RwLockCache BlockingQueueDemo \
	             SpscRingBuffer SpinLock StampedLockDemo FencesDemo \
	             ClassicBlockingQueue CasMpmcQueue; do \
		echo "--- java $$demo ---"; \
		(cd $(JAVA_DIR) && mvn -q exec:java -Dexec.mainClass=$$demo) || exit 1; \
	done

run-demos-cpp: build-cpp
	@for demo in mutex_ownership_demo volatile_publication cas_counter \
	             double_checked_locking rw_lock_cache blocking_queue_demo \
	             spsc_ring_buffer_demo spin_lock_demo seqlock_demo fences_demo \
	             cas_mpmc_queue_demo; do \
		echo "--- cpp $$demo ---"; \
		$(CPP_BUILD)/cpp/$$demo || exit 1; \
	done
	@echo "(queue_benchmark not included above -- it runs 4 configs x 2"
	@echo " implementations and takes longer; run it with 'make run-benchmark-cpp'."
	@echo " Java equivalent: 'make run-benchmark-java')"

run-benchmark-java: build-java
	@echo "--- java QueueBenchmark (~1-2 min: 4 contention levels x 2 queues) ---"
	cd $(JAVA_DIR) && mvn -q exec:java -Dexec.mainClass=QueueBenchmark

run-benchmark-cpp: build-cpp
	@echo "--- cpp queue_benchmark (~1-2 min: 4 contention levels x 2 queues) ---"
	$(CPP_BUILD)/cpp/queue_benchmark

# --- Clean -------------------------------------------------------------

clean:
	@echo "==> removing $(CPP_BUILD)/ (C++ binaries + Java classes)"
	rm -rf $(CPP_BUILD)
	@# Fallback: clean stray Maven output if pom was run with an old config.
	rm -rf $(JAVA_DIR)/target
