cmake_minimum_required(VERSION 3.22.1)
project(termux_capabilities C)
add_library(termux-capabilities SHARED capabilities.c)
target_compile_options(termux-capabilities PRIVATE -Wall -Wextra -Werror)
# Baseline instructions only: detection must not execute optional SIMD instructions.
target_link_options(termux-capabilities PRIVATE "-Wl,-z,max-page-size=16384")
