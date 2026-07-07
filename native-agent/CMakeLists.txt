cmake_minimum_required(VERSION 3.20)
# gnu-agent.so — native JVMTI agent (injected via gnu-inject --native)

set(IMGUI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/imgui)

set(IMGUI_SOURCES
    ${IMGUI_DIR}/imgui.cpp
    ${IMGUI_DIR}/imgui_draw.cpp
    ${IMGUI_DIR}/imgui_tables.cpp
    ${IMGUI_DIR}/imgui_widgets.cpp
    ${IMGUI_DIR}/imgui_impl_opengl3.cpp
)

# funchook (static) — options must precede add_subdirectory.
set(FUNCHOOK_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(FUNCHOOK_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(FUNCHOOK_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(FUNCHOOK_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/funchook)

set(AGENT_SOURCES
    src/entry.cpp
    src/log.cpp
    src/jvmti_context.cpp
    src/jni_bridge.cpp
    src/thread_worker.cpp
    src/glx_hook.cpp
    src/imgui_gui.cpp
    src/movement_input_transformer.cpp
    src/entity_player_transformer.cpp
    src/entity_living_base_transformer.cpp
    src/packet_transformer.cpp
    src/world_render_transformer.cpp
    src/gui_ingame_transformer.cpp
)

add_library(gnu-agent SHARED ${AGENT_SOURCES} ${IMGUI_SOURCES})

# JNI/JVMTI headers: the PrismLauncher Oracle JRE 1.8.0_202 ships NO headers, so
# compile against JDK 8 headers (ABI-compatible at runtime). We do NOT link
# libjvm — an attach agent receives JavaVM* from Agent_OnAttach.
set(GNU_JDK8_HOME "/usr/lib/jvm/java-8-openjdk" CACHE PATH "JDK 8 home for JNI headers")
if(NOT EXISTS "${GNU_JDK8_HOME}/include/jni.h")
    message(FATAL_ERROR "JNI headers not found at ${GNU_JDK8_HOME}/include. Set -DGNU_JDK8_HOME=<jdk8>.")
endif()

find_package(OpenGL REQUIRED)
find_package(X11 REQUIRED)
find_package(Threads REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(EVDEV REQUIRED IMPORTED_TARGET libevdev)

target_include_directories(gnu-agent PRIVATE
    ${GNU_JDK8_HOME}/include
    ${GNU_JDK8_HOME}/include/linux
    ${IMGUI_DIR}
    ${X11_INCLUDE_DIR}
    ${EVDEV_INCLUDE_DIRS}
    src
)

target_link_libraries(gnu-agent PRIVATE
    funchook-static
    OpenGL::GL
    ${X11_LIBRARIES}
    Xi
    Threads::Threads
    PkgConfig::EVDEV
    ${CMAKE_DL_LIBS}
)

# Produce gnu-agent.so (no "lib" prefix); injector resolves install/lib/gnu-agent.so.
set_target_properties(gnu-agent PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    PREFIX ""
    OUTPUT_NAME "gnu-agent"
)

target_compile_definitions(gnu-agent PRIVATE
    $<$<CONFIG:Debug>:GNU_DEBUG>
)

# Stage into install/lib at build time (mirrors the gnu-client.jar copy step).
# Fonts are loaded at runtime from <gnu-agent.so dir>/../fonts (install/fonts).
add_custom_command(TARGET gnu-agent POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_SOURCE_DIR}/install/lib
    COMMAND ${CMAKE_COMMAND} -E copy
        $<TARGET_FILE:gnu-agent> ${CMAKE_SOURCE_DIR}/install/lib/gnu-agent.so
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_SOURCE_DIR}/install/fonts
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        ${CMAKE_CURRENT_SOURCE_DIR}/fonts ${CMAKE_SOURCE_DIR}/install/fonts
    COMMENT "Staging gnu-agent.so -> install/lib and fonts -> install/fonts"
)

install(TARGETS gnu-agent
    LIBRARY DESTINATION ${CMAKE_SOURCE_DIR}/install/lib
)
