cmake_minimum_required(VERSION 3.22.1)

project(kinex_engine LANGUAGES CXX)

# one_euro.cpp, geometry.cpp and rep_fsm.cpp are not wired into process() yet — they are
# here so the device toolchain compiles them under the same -Werror as everything else,
# rather than finding out when the wiring lands.
add_library(kinex_engine SHARED
        engine.cpp
        one_euro.cpp
        geometry.cpp
        rep_fsm.cpp
        exercises/exercise_config.cpp
        jni_bridge.cpp)

target_compile_features(kinex_engine PRIVATE cxx_std_17)

# exercises/exercise_config.cpp includes its own header by the same "exercises/..." path the
# engine uses, so the include root is cpp/ rather than each file's own directory.
target_include_directories(kinex_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# The per-frame path has to stay allocation-free and warning-clean; -Werror keeps a
# stray copy or narrowing conversion from reaching a device.
target_compile_options(kinex_engine PRIVATE
        -Wall
        -Wextra
        -Werror)

# liblog serves handle create/destroy logging only. The design doc forbids logging from
# process() in release builds.
target_link_libraries(kinex_engine PRIVATE log)
