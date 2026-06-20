cmake_minimum_required(VERSION 3.20)
# C and ASM are required by vendored funchook (distorm + asm trampolines).
project(GNUClient LANGUAGES CXX C ASM)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(GNU_CLIENT_DIR ${CMAKE_SOURCE_DIR}/client)
set(GNU_CLIENT_JAR ${GNU_CLIENT_DIR}/build/libs/gnu-client.jar)
set(GNU_CLIENT_JAR_INSTALL ${CMAKE_SOURCE_DIR}/install/lib/gnu-client.jar)

if(EXISTS "${GNU_CLIENT_DIR}/gradlew")
    set(GRADLE_BUILD_CMD ${GNU_CLIENT_DIR}/gradlew)
else()
    find_program(GRADLE_EXE gradle REQUIRED)
    set(GRADLE_BUILD_CMD ${GRADLE_EXE})
endif()

# Track Java sources so cmake rebuilds the jar when they change (not only when OUTPUT is missing).
file(GLOB_RECURSE GNU_CLIENT_JAVA_SOURCES
    CONFIGURE_DEPENDS
    "${GNU_CLIENT_DIR}/src/main/java/*.java"
)
set(GNU_CLIENT_GRADLE_INPUTS
    ${GNU_CLIENT_JAVA_SOURCES}
    "${GNU_CLIENT_DIR}/build.gradle"
    "${GNU_CLIENT_DIR}/settings.gradle"
    "${GNU_CLIENT_DIR}/gradle/wrapper/gradle-wrapper.properties"
)

add_custom_command(
    OUTPUT ${GNU_CLIENT_JAR} ${GNU_CLIENT_JAR_INSTALL}
    COMMAND ${GRADLE_BUILD_CMD} shadowJar --no-daemon
    COMMAND ${CMAKE_COMMAND} -E copy ${GNU_CLIENT_JAR} ${GNU_CLIENT_JAR_INSTALL}
    WORKING_DIRECTORY ${GNU_CLIENT_DIR}
    DEPENDS ${GNU_CLIENT_GRADLE_INPUTS}
    COMMENT "Building gnu-client.jar (Gradle shadowJar)"
    VERBATIM
)

add_custom_target(gnu_client_jar DEPENDS ${GNU_CLIENT_JAR} ${GNU_CLIENT_JAR_INSTALL})

add_subdirectory(injector)
add_subdirectory(native-agent)
add_dependencies(gnu-inject gnu_client_jar)

# Unified workflow: jar + native agent + injector, all staged into install/.
add_custom_target(gnu-all DEPENDS gnu_client_jar gnu-agent gnu-inject)
