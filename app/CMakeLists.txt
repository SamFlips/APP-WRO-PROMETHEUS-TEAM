# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.10)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("opencv_prueba")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Ruta de OpenCV
set(OpenCV_DIR "D:/OpenCV-android-sdk/sdk/native/jni")

find_package(OpenCV REQUIRED)

add_library(native-lib SHARED src/main/cpp/native-lib.cpp)
target_link_libraries(native-lib ${OpenCV_LIBS})
include_directories("D:/OpenCV-android-sdk/sdk/native/jni/include")
add_library(libopencv_java4 SHARED IMPORTED)
set_target_properties(libopencv_java4 PROPERTIES IMPORTED_LOCATION
        "D:/OpenCV-android-sdk/sdk/native/libs/${ANDROID_ABI}/libopencv_java4.so")