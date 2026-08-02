package com.neveroff.crypto

import android.util.Log

/**
 * Native Rust crypto operations via JNI.
 * Provides ~5-10x speedup over pure Java/Kotlin crypto for:
 * - ECDH key agreement (P-256)
 * - AES-256-GCM encryption/decryption
 * - HMAC-SHA256 KDF
 * - Batch message decryption (single JNI crossing)
 *
 * The Rust library must be compiled via `rust/build.sh` and placed in
 * `app/src/main/jniLibs/{arm64-v8a,x86_64}/libcrypto_core.so`
 */
object CryptoNative {
    private const val TAG = "CryptoNative"

    /**
     * Whether the native library is available.
     * Falls back to Kotlin implementation if false.
     */
    val isAvailable: Boolean

    init {
        isAvailable = try {
            System.loadLibrary("crypto_core")
            Log.i(TAG, "Loaded libcrypto_core.so — native crypto enabled")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libcrypto_core.so not found — falling back to Kotlin crypto")
            false
        }
    }

    /**
     * Perform ECDH P-256 key agreement.
     * @param localPrivateKey PKCS8 DER-encoded private key bytes
     * @param remotePublicKey X509 DER-encoded public key bytes
     * @return 32-byte shared secret
     */
    @JvmStatic
    external fun ecdhDerive(localPrivateKey: ByteArray, remotePublicKey: ByteArray): ByteArray

    /**
     * Encrypt using AES-256-GCM.
     * @param plaintext raw bytes to encrypt
     * @param key 32-byte AES key
     * @return IV (12 bytes) + ciphertext + tag concatenated
     */
    @JvmStatic
    external fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray): ByteArray

    /**
     * Decrypt AES-256-GCM ciphertext.
     * @param combined IV (12 bytes) + ciphertext + tag
     * @param key 32-byte AES key
     * @return decrypted plaintext bytes
     */
    @JvmStatic
    external fun aesGcmDecrypt(combined: ByteArray, key: ByteArray): ByteArray

    /**
     * Derive AES key from auth_key and msg_key using HMAC-SHA256.
     * @param authKey shared secret bytes
     * @param msgKey message key bytes
     * @return 32-byte derived AES key
     */
    @JvmStatic
    external fun deriveAesKey(authKey: ByteArray, msgKey: ByteArray): ByteArray

    /**
     * Batch decrypt multiple messages in a single JNI call.
     * Avoids JNI crossing overhead for each message.
     * @param ciphertextsB64 Base64-encoded ciphertexts
     * @param msgKeysB64 Base64-encoded message keys
     * @param authKey shared secret bytes
     * @return array of decrypted strings (or "[Encrypted message]" for failures)
     */
    @JvmStatic
    external fun batchDecrypt(
        ciphertextsB64: Array<String>,
        msgKeysB64: Array<String>,
        authKey: ByteArray
    ): Array<String>
}
