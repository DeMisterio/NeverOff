#!/bin/bash
# Build Rust crypto-core for Android targets
# Requirements:
#   rustup target add aarch64-linux-android x86_64-linux-android
#   cargo install cargo-ndk
#   Set ANDROID_NDK_HOME environment variable

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/crypto-core"

echo "Building crypto-core for Android..."

# Build for arm64 (physical devices) and x86_64 (emulator)
cargo ndk \
    --target aarch64-linux-android \
    --target x86_64-linux-android \
    --platform 26 \
    -- build --release

# Copy .so files to the JNI libs directory
JNI_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"

mkdir -p "$JNI_DIR/arm64-v8a"
mkdir -p "$JNI_DIR/x86_64"

cp "target/aarch64-linux-android/release/libcrypto_core.so" "$JNI_DIR/arm64-v8a/"
cp "target/x86_64-linux-android/release/libcrypto_core.so" "$JNI_DIR/x86_64/"

echo "✅ Built and copied .so files to $JNI_DIR"
echo "   arm64-v8a: $(ls -lh "$JNI_DIR/arm64-v8a/libcrypto_core.so" | awk '{print $5}')"
echo "   x86_64:    $(ls -lh "$JNI_DIR/x86_64/libcrypto_core.so" | awk '{print $5}')"
