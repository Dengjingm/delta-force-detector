# Native Daemon Build (CMake)

cmake_minimum_required(VERSION 3.22)
project(screen-visiond C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_executable(screen-visiond
    main.c
    screencap.c
    socket_server.c
)

target_link_libraries(screen-visiond
    android
    log
    dl
)

target_compile_options(screen-visiond PRIVATE
    -Wall -Wextra -Werror
    -O2
)

install(TARGETS screen-visiond RUNTIME DESTINATION bin)