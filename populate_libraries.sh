#!/usr/bin/env bash

workdir=output
jnadir=${workdir}/jna
libsdir=${workdir}/libs
jniLibs=${workdir}/jniLibs
vcx_version=0.13.1

# mkdir -p ${jniLibs}/armeabi-v7a
# mkdir -p ${jniLibs}/arm64-v8a
mkdir -p ${jniLibs}/x86
mkdir -p ${jniLibs}/x86_64
mkdir -p ${libsdir}
mkdir -p ${jnadir}

download_prebuilt_vcx(){
   pushd ${libsdir}
   wget "https://github.com/hyperledger/aries-vcx/releases/download/${vcx_version}/libvcx-android-${vcx_version}-emulator.aar"
   popd
}

download_ndk(){
    pushd ${workdir}
    if [ "$(uname)" == "Darwin" ]; then
        echo "Downloading NDK for macOS"
        wget -O ndk_r20.zip "https://dl.google.com/android/repository/android-ndk-r20-darwin-x86_64.zip"
    elif [ "$(expr substr $(uname -s) 1 5)" == "Linux" ]; then
        echo "Downloading NDK for Linux"
        wget -O ndk_r21b.zip "https://dl.google.com/android/repository/android-ndk-r21b-linux-x86_64.zip"
    fi
    unzip ndk_r20.zip
    popd 
}

download_jna(){
    pushd ${jnadir}
    # wget -O jna-android-armv7.jar "https://github.com/java-native-access/jna/raw/4.5.2/lib/native/android-armv7.jar"
    # wget -O jna-android-arm64.jar "https://github.com/java-native-access/jna/raw/4.5.2/lib/native/android-aarch64.jar"
    wget -O jna-android-x86.jar "https://github.com/java-native-access/jna/raw/4.5.2/lib/native/android-x86.jar"
    wget -O jna-android-x86-64.jar "https://github.com/java-native-access/jna/raw/4.5.2/lib/native/android-x86-64.jar"
    popd
}

copy_native_libraries(){
    pushd ${workdir}
    # cp android-ndk-r20/sources/cxx-stl/llvm-libc++/libs/armeabi-v7a/libc++_shared.so jniLibs/armeabi-v7a/
    # cp android-ndk-r20/sources/cxx-stl/llvm-libc++/libs/arm64-v8a/libc++_shared.so jniLibs/arm64-v8a/
    cp android-ndk-r20/sources/cxx-stl/llvm-libc++/libs/x86/libc++_shared.so jniLibs/x86/
    cp android-ndk-r20/sources/cxx-stl/llvm-libc++/libs/x86_64/libc++_shared.so jniLibs/x86_64/

    # unzip jna/jna-android-armv7.jar libjnidispatch.so -d jniLibs/armeabi-v7a/
    # unzip jna/jna-android-arm64.jar libjnidispatch.so -d jniLibs/arm64-v8a/
    unzip jna/jna-android-x86.jar libjnidispatch.so -d jniLibs/x86/
    unzip jna/jna-android-x86-64.jar libjnidispatch.so -d jniLibs/x86_64/

    cp -r jniLibs ../app/src/main
    cp -r libs ../app
    popd
}

cleanup(){
    rm -rf output
}

download_prebuilt_vcx
download_ndk
download_jna
copy_native_libraries
cleanup
