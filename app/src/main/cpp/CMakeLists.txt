cmake_minimum_required(VERSION 3.22.1)
project(termux_capabilities C)
add_library(termux-capabilities SHARED capabilities.c)
target_compile_options(termux-capabilities PRIVATE -Wall -Wextra -Werror)
# Baseline instructions only: detection must not execute optional SIMD instructions.
target_link_options(termux-capabilities PRIVATE "-Wl,-z,max-page-size=16384")
add_library(termux-arch-vsock SHARED arch-vsock.c)
target_compile_options(termux-arch-vsock PRIVATE -Wall -Wextra -Werror)
target_link_options(termux-arch-vsock PRIVATE "-Wl,-z,max-page-size=16384")
