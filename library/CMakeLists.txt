cmake_minimum_required(VERSION 3.20)
project(nativecanvas LANGUAGES C)
find_package(JNI REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(GRAPHICS REQUIRED IMPORTED_TARGET cairo pangocairo sdl3)
add_library(nativecanvas SHARED native/canvas.c)
target_compile_features(nativecanvas PRIVATE c_std_11)
target_include_directories(nativecanvas PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(nativecanvas PRIVATE PkgConfig::GRAPHICS)
# Keep the JNI library at the same location for single- and multi-config generators.
set_target_properties(nativecanvas PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>"
    RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>")

option(NATIVECANVAS_BUILD_TESTS "Build the SDL event fixture for Java tests" OFF)
if(NATIVECANVAS_BUILD_TESTS)
    add_library(nativecanvas_test SHARED src/test/native/events.c)
    target_compile_features(nativecanvas_test PRIVATE c_std_11)
    target_include_directories(nativecanvas_test PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(nativecanvas_test PRIVATE PkgConfig::GRAPHICS)
    set_target_properties(nativecanvas_test PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>"
        RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>")
endif()
