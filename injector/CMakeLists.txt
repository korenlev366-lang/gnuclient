set(INJECTOR_SOURCES
    src/main.cpp
    src/process_scanner.cpp
    src/jvm_attach.cpp
)

add_executable(gnu-inject ${INJECTOR_SOURCES})

target_include_directories(gnu-inject PRIVATE src)

find_package(Threads REQUIRED)
target_link_libraries(gnu-inject PRIVATE Threads::Threads)

# Stage into install/bin at build time so default jar/agent paths resolve
# (install/bin/gnu-inject alongside install/lib/{gnu-client.jar,gnu-agent.so}).
add_custom_command(TARGET gnu-inject POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_SOURCE_DIR}/install/bin
    COMMAND ${CMAKE_COMMAND} -E copy
        $<TARGET_FILE:gnu-inject> ${CMAKE_SOURCE_DIR}/install/bin/gnu-inject
    COMMENT "Staging gnu-inject -> install/bin"
)

install(TARGETS gnu-inject
    RUNTIME DESTINATION ${CMAKE_SOURCE_DIR}/install/bin
)
