cmake_minimum_required(VERSION 3.22.1)

project(pjsk_native)

add_library(pjsk_native SHARED native_detector.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

set(NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}")
set(NCNN_INCLUDE "${NCNN_ROOT}/include")
set(NCNN_LIB "${NCNN_ROOT}/lib/libncnn.a")
set(NCNN_CMAKE_DIR "${NCNN_ROOT}/lib/cmake/ncnn")

if(EXISTS "${NCNN_CMAKE_DIR}/ncnnConfig.cmake")
    set(ncnn_DIR "${NCNN_CMAKE_DIR}" CACHE PATH "ncnn config directory" FORCE)
    find_package(ncnn REQUIRED CONFIG)
    target_compile_definitions(pjsk_native PRIVATE PJSK_WITH_NCNN=1)
    target_link_libraries(pjsk_native ncnn)
elseif(EXISTS "${NCNN_INCLUDE}/ncnn/net.h" AND EXISTS "${NCNN_LIB}")
    add_library(ncnn STATIC IMPORTED)
    set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION "${NCNN_LIB}")
    target_include_directories(pjsk_native PRIVATE "${NCNN_INCLUDE}/ncnn")
    target_compile_definitions(pjsk_native PRIVATE PJSK_WITH_NCNN=1)
    target_link_libraries(pjsk_native ncnn)
else()
    message(FATAL_ERROR "NCNN Android SDK not found at ${NCNN_ROOT}")
endif()

target_link_libraries(
        pjsk_native
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib})
