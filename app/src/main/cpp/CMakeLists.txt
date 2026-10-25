cmake_minimum_required(VERSION 3.22.1)
project(leiahack C)

# Tiny shim so Kotlin can invoke a C++ vtable slot on CNSDK's interlacer (see leiahack.c).
add_library(leiahack SHARED leiahack.c)
find_library(log-lib log)
target_link_libraries(leiahack ${log-lib})
