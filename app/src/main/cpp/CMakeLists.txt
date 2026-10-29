cmake_minimum_required(VERSION 3.22.1)
project(dsdplayer LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── 预编译的 libusb-1.0（scripts/build_libusb.sh 生成）────────────────────
add_library(libusb STATIC IMPORTED)
set_target_properties(libusb PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libusb-1.0.a)

# ── Apple ALACDecoder（Apache-2.0，见 alac/APPLE_LICENSE.txt）──────────
# AOSP 不带 ALAC 解码器，iTunes 购买/抓轨得到的无 DRM ALAC 由它在应用内解码。
add_library(alac STATIC
    alac/ALACDecoder.cpp
    alac/ALACBitUtilities.c
    alac/EndianPortable.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c
)
target_include_directories(alac PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/alac)

add_library(dsdplayer SHARED
    native/jni_bridge.cpp
    native/uac2_raw.cpp
    native/usb_stream.cpp
    native/dsd_native.cpp
    native/alac_jni.cpp
)

target_include_directories(dsdplayer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}          # 让 native/alac_jni.cpp 能 #include "alac/..."
)

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(dsdplayer libusb alac ${log-lib} ${android-lib})
