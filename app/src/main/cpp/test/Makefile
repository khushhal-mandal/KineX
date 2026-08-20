# Host-side tests. No device, no emulator: `make test` for the engine's unit tests,
# `make soak` for the JNI bridge's leak soak, `make asan` for memory errors.
#
# GoogleTest comes from the NDK, which ships it at sources/third_party/googletest — the
# same NDK this Makefile already resolves for jni.h. Nothing to install, nothing to
# fetch, and the version is pinned to the toolchain the app is built against anyway.
#
# LeakSanitizer is used wherever it exists. It does not exist on macOS — clang rejects
# -fsanitize=leak for arm64-apple-darwin, and ASan's detect_leaks is unsupported on
# Darwin too — so there the same pass/fail contract (zero leaked bytes) is enforced with
# the system `leaks` tool instead. ASan and `leaks` cannot be combined: ASan's allocator
# hides allocations from `leaks`, and scanning its shadow memory hangs. So the leak
# check builds clean on Darwin, and `make asan` covers memory errors separately.

UNAME_S := $(shell uname -s)

CXX ?= clang++
ENGINE_DIR := ..
EXERCISE_DIR := ../exercises
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
NDK ?= $(lastword $(sort $(wildcard $(ANDROID_HOME)/ndk/*)))
JNI_INCLUDE := $(firstword $(wildcard $(NDK)/toolchains/llvm/prebuilt/*/sysroot/usr/include))

GTEST := $(NDK)/sources/third_party/googletest

# process() now runs the whole per-frame pipeline, so the soak links it: filter, geometry,
# FSM and the exercise config the FSM normalizes against.
SOURCES := $(ENGINE_DIR)/engine.cpp $(ENGINE_DIR)/jni_bridge.cpp \
           $(ENGINE_DIR)/one_euro.cpp $(ENGINE_DIR)/geometry.cpp $(ENGINE_DIR)/rep_fsm.cpp \
           $(EXERCISE_DIR)/exercise_config.cpp soak_test.cpp

# squat_fixture.cpp replays the recording through the filter, so every test binary that
# touches the fixture links one_euro.cpp whether or not it is testing the filter.
TEST_SUPPORT := squat_fixture.cpp $(ENGINE_DIR)/one_euro.cpp
TEST_HEADERS := squat_fixture.h $(ENGINE_DIR)/one_euro.h $(ENGINE_DIR)/landmark_layout.h \
                $(ENGINE_DIR)/geometry.h $(EXERCISE_DIR)/exercise_config.h
ONE_EURO_SOURCES := one_euro_test.cpp $(TEST_SUPPORT)
GEOMETRY_SOURCES := geometry_test.cpp $(ENGINE_DIR)/geometry.cpp $(TEST_SUPPORT)
# The FSM normalizes every frame through RepProgress(), so exercise_config.cpp is on its
# link line now — not only the test's, the engine's.
REP_FSM_SOURCES := rep_fsm_test.cpp $(ENGINE_DIR)/rep_fsm.cpp $(ENGINE_DIR)/geometry.cpp \
                   $(EXERCISE_DIR)/exercise_config.cpp $(TEST_SUPPORT)
EXERCISE_SOURCES := exercise_config_test.cpp $(EXERCISE_DIR)/exercise_config.cpp \
                    $(ENGINE_DIR)/geometry.cpp

# -idirafter, not -I: the NDK sysroot must sit *behind* the host's own headers, or the
# host compiler picks up Android's stdio.h and follows it into Linux kernel headers.
# jni.h has no macOS equivalent, so it still resolves from the NDK.
CXXFLAGS := -std=c++17 -O2 -g -Wall -Wextra -Werror \
            -I$(ENGINE_DIR) -Istubs -idirafter $(JNI_INCLUDE)

ifeq ($(UNAME_S),Linux)
  LEAK_FLAGS := -fsanitize=address,leak -fno-omit-frame-pointer
else
  LEAK_FLAGS :=
endif

LEAK_BIN := soak_leak
ASAN_BIN := soak_asan
TEST_BINS := one_euro_test geometry_test rep_fsm_test exercise_config_test

# GoogleTest is third-party: it does not get this project's -Werror, and its headers come
# in through -isystem so its own warnings stay out of the test build's output.
GTEST_CXXFLAGS := -std=c++17 -O2 -g -I$(GTEST)/include -I$(GTEST)
TEST_CXXFLAGS := -std=c++17 -O2 -g -Wall -Wextra -Werror \
                 -I$(ENGINE_DIR) -isystem $(GTEST)/include

.PHONY: test soak asan clean check-jni check-gtest

# Every binary rule below deletes its target before building it.
#
# A failed compile otherwise leaves the *previous* binary on disk, untouched and still
# runnable. `make test` does not run it — make will not run a recipe whose prerequisite
# failed, even under -k — but anything that invokes ./rep_fsm_test directly after a failed
# build silently tests the last version that compiled, and reports it as a pass. That is a
# wrong answer rather than an error, which is the worst kind.
#
# .DELETE_ON_ERROR does not cover this on its own: GNU make only deletes a target it
# observed change, and a compile that errors out never writes one. It is here for the case
# that does write one — a link killed partway, leaving a truncated binary with a fresh
# mtime that the next make would consider up to date.
.DELETE_ON_ERROR:

check-jni:
	@test -f "$(JNI_INCLUDE)/jni.h" || { \
	  echo "jni.h not found. Set ANDROID_HOME or NDK (looked in $(JNI_INCLUDE))"; exit 1; }

check-gtest:
	@test -f "$(GTEST)/src/gtest-all.cc" || { \
	  echo "googletest not found. Set ANDROID_HOME or NDK (looked in $(GTEST))"; exit 1; }

$(LEAK_BIN): check-jni $(SOURCES) $(ENGINE_DIR)/engine.h
	@rm -f $@
	$(CXX) $(CXXFLAGS) $(LEAK_FLAGS) -o $@ $(SOURCES)

$(ASAN_BIN): check-jni $(SOURCES) $(ENGINE_DIR)/engine.h
	@rm -f $@
	$(CXX) $(CXXFLAGS) -fsanitize=address,undefined -fno-omit-frame-pointer -o $@ $(SOURCES)

gtest-all.o: check-gtest
	$(CXX) $(GTEST_CXXFLAGS) -c -o $@ $(GTEST)/src/gtest-all.cc

gtest_main.o: check-gtest
	$(CXX) $(GTEST_CXXFLAGS) -c -o $@ $(GTEST)/src/gtest_main.cc

one_euro_test: $(ONE_EURO_SOURCES) $(TEST_HEADERS) gtest-all.o gtest_main.o
	@rm -f $@
	$(CXX) $(TEST_CXXFLAGS) -o $@ $(ONE_EURO_SOURCES) gtest-all.o gtest_main.o

geometry_test: $(GEOMETRY_SOURCES) $(TEST_HEADERS) gtest-all.o gtest_main.o
	@rm -f $@
	$(CXX) $(TEST_CXXFLAGS) -o $@ $(GEOMETRY_SOURCES) gtest-all.o gtest_main.o

rep_fsm_test: $(REP_FSM_SOURCES) $(TEST_HEADERS) $(ENGINE_DIR)/rep_fsm.h \
              gtest-all.o gtest_main.o
	@rm -f $@
	$(CXX) $(TEST_CXXFLAGS) -o $@ $(REP_FSM_SOURCES) gtest-all.o gtest_main.o

exercise_config_test: $(EXERCISE_SOURCES) $(EXERCISE_DIR)/exercise_config.h \
                      $(ENGINE_DIR)/geometry.h gtest-all.o gtest_main.o
	@rm -f $@
	$(CXX) $(TEST_CXXFLAGS) -o $@ $(EXERCISE_SOURCES) gtest-all.o gtest_main.o

test: $(TEST_BINS)
	@set -e; for bin in $(TEST_BINS); do echo "== $$bin =="; ./$$bin; done

# Both soaks must report zero leaked bytes AND pass their own contract checks. Two
# invocations per mode, each judged by its exit status alone. Nothing parses any output.
#
# The first runs the soak bare. soak_test.cpp returns 1 for a broken JNI contract — a fresh
# array per frame, a live reference at exit — which leaks nothing at all and so is invisible
# to any leak checker. That verdict is its exit code, and `set -e` is what reads it.
#
# The second puts the same soak under the leak checker, whose exit status is the leak verdict
# and whose output is the diagnostic when it fails. On Linux that checker is the binary
# itself, already built with -fsanitize=address,leak; on Darwin, where LSan does not exist,
# it is leaks(1) wrapped around the clean build.
#
# The previous version of this rule ran once and grepped the combined output for both
# verdicts, because `leaks` exits non-zero on a leak and that discarded the binary's own
# status. Reading a pass out of text is how a grep for "leaks" missed the singular form and
# reported a pass it had not earned. Exit codes do not have a plural.
soak: $(LEAK_BIN)
	@set -e; \
	for mode in process cycles; do \
	  echo "== soak contract: $$mode =="; \
	  ./$(LEAK_BIN) $$mode; \
	  echo "== leak check: $$mode =="; \
	  if [ "$(UNAME_S)" = "Darwin" ]; then \
	    leaks --atExit -- ./$(LEAK_BIN) $$mode; \
	  else \
	    ASAN_OPTIONS=detect_leaks=1 LSAN_OPTIONS=exitcode=23 ./$(LEAK_BIN) $$mode; \
	  fi; \
	done; \
	echo "== both soaks clean: zero bytes leaked =="

# Memory errors (overruns on the 132-float marshalling, UB) — no leak detection here.
asan: $(ASAN_BIN)
	@echo "== asan: process =="; ./$(ASAN_BIN) process
	@echo "== asan: cycles ==";  ./$(ASAN_BIN) cycles

clean:
	rm -rf $(LEAK_BIN) $(ASAN_BIN) $(TEST_BINS) *.o *.dSYM
