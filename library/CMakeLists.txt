cmake_minimum_required(VERSION 3.20)
project(nativecanvas LANGUAGES C)
find_package(JNI REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(SDL REQUIRED IMPORTED_TARGET sdl3)
add_library(nativecanvas SHARED native/canvas.c native/backend_registry.c)
target_compile_features(nativecanvas PRIVATE c_std_11)
target_include_directories(nativecanvas PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(nativecanvas PRIVATE PkgConfig::SDL)
# Keep the JNI library at the same location for single- and multi-config generators.
set_target_properties(nativecanvas PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>"
    RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>")

option(NATIVECANVAS_CAIRO "Build the Cairo backend" ON)
if(NATIVECANVAS_CAIRO)
    pkg_check_modules(CAIRO REQUIRED IMPORTED_TARGET cairo pangocairo)
    add_library(nativecanvas_cairo STATIC native/cairo_backend.c)
    set_target_properties(nativecanvas_cairo PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_compile_features(nativecanvas_cairo PRIVATE c_std_11)
    target_link_libraries(nativecanvas_cairo PRIVATE PkgConfig::SDL PkgConfig::CAIRO)
    target_compile_definitions(nativecanvas PRIVATE NATIVECANVAS_HAS_CAIRO)
    target_link_libraries(nativecanvas PRIVATE nativecanvas_cairo)
endif()

option(NATIVECANVAS_BUILD_TESTS "Build the SDL event fixture for Java tests" OFF)
if(NATIVECANVAS_BUILD_TESTS)
    add_library(nativecanvas_test SHARED src/test/native/events.c)
    target_compile_features(nativecanvas_test PRIVATE c_std_11)
    target_include_directories(nativecanvas_test PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(nativecanvas_test PRIVATE PkgConfig::SDL)
    set_target_properties(nativecanvas_test PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>"
        RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>")
endif()

set(SKIA_DEFAULT OFF)
if(APPLE AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64)$")
    set(SKIA_DEFAULT ON)
endif()
option(NATIVECANVAS_SKIA "Build the Skia Metal backend (macOS arm64)" ${SKIA_DEFAULT})
if(NATIVECANVAS_SKIA)
    if(NOT APPLE OR NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64)$")
        message(FATAL_ERROR "Skia binaries currently support macOS arm64; use -DNATIVECANVAS_SKIA=OFF for Cairo")
    endif()
    enable_language(CXX OBJCXX)
    set(SKIA_ROOT "" CACHE PATH "Unpacked SkiaBuild m143 macOS arm64 distribution")
    if(NOT SKIA_ROOT)
        include(FetchContent)
        FetchContent_Declare(skia
            URL https://github.com/HumbleUI/SkiaBuild/releases/download/m143-da51f0d60e-5/Skia-m143-da51f0d60e-5-macos-Release-arm64.zip
            URL_HASH SHA256=3a27e8661f35728802dc9bb862d30459381ea72ec5407cf8c2593e4546fb938b)
        FetchContent_MakeAvailable(skia)
        set(SKIA_ROOT "${skia_SOURCE_DIR}")
    endif()
    add_library(nativecanvas_skia STATIC native/skia_backend.mm)
    set_target_properties(nativecanvas_skia PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_compile_definitions(nativecanvas PRIVATE NATIVECANVAS_HAS_SKIA)
    target_link_libraries(nativecanvas PRIVATE nativecanvas_skia)
    target_include_directories(nativecanvas_skia PRIVATE "${SKIA_ROOT}")
    set_source_files_properties(native/skia_backend.mm PROPERTIES COMPILE_OPTIONS "-fobjc-arc")
    target_compile_features(nativecanvas_skia PRIVATE cxx_std_17)
    target_compile_definitions(nativecanvas_skia PRIVATE SK_GANESH SK_METAL)
    file(GLOB SKIA_ARCHIVES "${SKIA_ROOT}/out/Release-arm64/*.a")
    target_link_libraries(nativecanvas_skia PRIVATE PkgConfig::SDL ${SKIA_ARCHIVES}
        "-framework Metal" "-framework QuartzCore" "-framework CoreText"
        "-framework CoreGraphics" "-framework Foundation" "-framework AppKit")
endif()


if(NOT NATIVECANVAS_CAIRO AND NOT NATIVECANVAS_SKIA)
    message(FATAL_ERROR "Enable at least one rendering backend")
endif()
