cmake_minimum_required(VERSION 3.22.1)

project(photo_search_native)

add_library(photo_search SHARED vector_search_jni.cpp)

target_compile_features(photo_search PRIVATE cxx_std_17)
target_compile_options(photo_search PRIVATE -O3)
