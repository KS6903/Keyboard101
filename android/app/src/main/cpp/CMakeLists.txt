cmake_minimum_required(VERSION 3.22.1)
project("keyboard101_pinyin")

add_library(pinyin_jni SHARED pinyin_jni.cpp)

target_include_directories(pinyin_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(pinyin_jni ${log-lib})

target_compile_options(pinyin_jni PRIVATE -O2 -std=c++17)
