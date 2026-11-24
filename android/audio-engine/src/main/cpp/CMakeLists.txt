cmake_minimum_required(VERSION 3.22.1)
project(vmaudio LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(vmaudio SHARED jni_bridge.cpp audio_engine.cpp)
target_link_libraries(vmaudio oboe::oboe log)
