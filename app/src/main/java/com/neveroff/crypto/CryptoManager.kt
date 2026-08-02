package com.neveroff.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.File
import android.util.Base64
import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import java.security.spec.ECGenParameterSpec

/**
 * CryptoManager handles:
 * 1. Identity key generation (EC-P256) in software, wrapped by an AES Keystore key for compatibility.
 * 2. Diffie-Hellman Key Exchange to derive a shared secret (auth_key).
 * 3. E2E Encryption using AES-GCM for messages.
 */
object CryptoManager {
    private const val MASTER_KEY_ALIAS = "neveroff_master_aes_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val PREFS_NAME = "neveroff_crypto_prefs"
    private const val AUTH_KEY_CACHE_MAX = 50
    
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    /** LRU cache for derived shared secrets keyed by remote public key Base64. */
    private val authKeyCache: LinkedHashMap<String, ByteArray> =
        object : LinkedHashMap<String, ByteArray>(AUTH_KEY_CACHE_MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
                size > AUTH_KEY_CACHE_MAX
        }

    /** Cached identity key pair to avoid repeated Keystore decryption. */
    @Volatile
    private var cachedKeyPair: KeyPair? = null

    /** Cached database key to avoid repeated Keystore decryption. */
    @Volatile
    private var cachedDbKey: String? = null

    init {
        AeadConfig.register()
        DeterministicAeadConfig.register()
    }
    
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::prefs.isInitialized) {
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun ensurePrefsInitialized(): Boolean {
        if (!::prefs.isInitialized) {
            if (::appContext.isInitialized) {
                prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
        return ::prefs.isInitialized
    }

    private fun getOrGenerateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val parameterSpec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
             .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
             .setKeySize(256)
             .build()
            keyGenerator.init(parameterSpec)
            keyGenerator.generateKey()
        }
        return keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
    }

    /**
     * Generates an EC-P256 key pair in software and wraps it with the Keystore AES key.
     */
    @Synchronized
    fun getOrGenerateKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }
        ensurePrefsInitialized()

        val pubKeyBase64 = if (::prefs.isInitialized) prefs.getString("ec_pub", null) else null
        val privKeyEncBase64 = if (::prefs.isInitialized) prefs.getString("ec_priv_enc", null) else null
        val ivBase64 = if (::prefs.isInitialized) prefs.getString("ec_iv", null) else null
        
        if (pubKeyBase64 != null && privKeyEncBase64 != null && ivBase64 != null) {
            try {
                val pubBytes = Base64.decode(pubKeyBase64, Base64.NO_WRAP)
                val privEncBytes = Base64.decode(privKeyEncBase64, Base64.NO_WRAP)
                val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
                
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrGenerateMasterKey(), GCMParameterSpec(128, iv))
                val privBytes = cipher.doFinal(privEncBytes)
                
                val keyFactory = KeyFactory.getInstance("EC")
                val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
                val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                return KeyPair(publicKey, privateKey).also { cachedKeyPair = it }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to regenerate if corrupted
            }
        }
        
        // Generate new software key pair
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()
        
        // Encrypt private key
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrGenerateMasterKey())
        val iv = cipher.iv
        val encryptedPriv = cipher.doFinal(keyPair.private.encoded)
        
        // Save to SharedPreferences
        prefs.edit()
            .putString("ec_pub", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
            .putString("ec_priv_enc", Base64.encodeToString(encryptedPriv, Base64.NO_WRAP))
            .putString("ec_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
            
        return keyPair.also { cachedKeyPair = it }
    }

    /**
     * Returns the Base64 encoded public key to be shared with the server.
     */
    fun getPublicKeyBase64(): String {
        val publicKey = getOrGenerateKeyPair().public
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Performs ECDH to derive a shared secret.
     */
    fun deriveSharedSecret(remotePublicKeyBase64: String): ByteArray {
        synchronized(authKeyCache) {
            authKeyCache[remotePublicKeyBase64]?.let { return it }
        }

        val keyPair = getOrGenerateKeyPair()
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(keyPair.private)

        val remotePublicKeyBytes = Base64.decode(remotePublicKeyBase64, Base64.NO_WRAP)
        val keyFactory = KeyFactory.getInstance("EC")
        val remotePublicKey = keyFactory.generatePublic(X509EncodedKeySpec(remotePublicKeyBytes))

        keyAgreement.doPhase(remotePublicKey, true)
        val secret = keyAgreement.generateSecret()

        synchronized(authKeyCache) {
            authKeyCache[remotePublicKeyBase64] = secret
        }
        return secret
    }

    /**
     * KDF to derive a 256-bit AES key from the shared secret and msg_key.
     */
    private fun deriveAesKey(authKey: ByteArray, msgKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(authKey, "HmacSHA256"))
        mac.update(msgKey)
        return mac.doFinal().sliceArray(0 until 32)
    }

    /**
     * Encrypts plaintext using AES-GCM (via Tink) and a derived key.
     * Returns a Pair of (ciphertext_base64, msg_key_base64).
     */
    fun encryptMessage(plaintext: String, authKey: ByteArray): Pair<String, String> {
        // 1. Generate a random msg_key
        val msgKey = ByteArray(16)
        SecureRandom().nextBytes(msgKey)

        // 2. Derive AES key
        val aesKey = deriveAesKey(authKey, msgKey)

        // 3. Encrypt with AES-GCM (Tink simplifies this)
        // For simplicity in this v1, we use standard javax.crypto for AES-GCM 
        // because Tink KeysetHandle usually expects a persistent keyset.
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val secureRandom = SecureRandom()
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val parameterSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), parameterSpec)
        
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Combine IV and ciphertext for transport
        val combined = iv + ciphertext
        
        return Base64.encodeToString(combined, Base64.NO_WRAP) to Base64.encodeToString(msgKey, Base64.NO_WRAP)
    }

    /**
     * Decrypts ciphertext using AES-GCM and the derived key.
     */
    fun decryptMessage(combinedCiphertextBase64: String, msgKeyBase64: String, authKey: ByteArray): String {
        val combined = Base64.decode(combinedCiphertextBase64, Base64.NO_WRAP)
        val msgKey = Base64.decode(msgKeyBase64, Base64.NO_WRAP)
        
        val aesKey = deriveAesKey(authKey, msgKey)
        
        val iv = combined.sliceArray(0 until 12)
        val ciphertext = combined.sliceArray(12 until combined.size)
        
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val parameterSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), parameterSpec)
        
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
    
    /**
     * Generates a 256-bit random key for SQLCipher and stores it in Keystore.
     */
    fun getOrGenerateDatabaseKey(): String {
        cachedDbKey?.let { return it }

        val encKeyB64 = prefs.getString("db_key_enc", null)
        val ivB64 = prefs.getString("db_key_iv", null)

        if (encKeyB64 != null && ivB64 != null) {
            return try {
                val encBytes = Base64.decode(encKeyB64, Base64.NO_WRAP)
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrGenerateMasterKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(encBytes), Charsets.UTF_8).also { cachedDbKey = it }
            } catch (e: Exception) {
                e.printStackTrace()
                // Key corrupted — regenerate and wipe old DB to prevent SQLiteException
                if (this::appContext.isInitialized) {
                    appContext.deleteDatabase("neveroff_db")
                }
                generateAndStoreDbKey().also { cachedDbKey = it }
            }
        }
        return generateAndStoreDbKey().also { cachedDbKey = it }
    }

    private fun generateAndStoreDbKey(): String {
        val rawKey = ByteArray(32)
        SecureRandom().nextBytes(rawKey)
        // Encode as hex string for SQLCipher compatibility
        val hexKey = rawKey.joinToString("") { "%02x".format(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrGenerateMasterKey())
        val encBytes = cipher.doFinal(hexKey.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString("db_key_enc", Base64.encodeToString(encBytes, Base64.NO_WRAP))
            .putString("db_key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return hexKey
    }

    /**
     * Encrypts a file using AES/GCM and returns the random AES key used (Base64).
     * The IV (12 bytes) is prepended to the output file.
     */
    fun encryptFile(inputFile: File, outputFile: File): String {
        val fileKey = ByteArray(32)
        SecureRandom().nextBytes(fileKey)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"))
        
        java.io.FileInputStream(inputFile).use { fis ->
            java.io.FileOutputStream(outputFile).use { fos ->
                fos.write(cipher.iv) // Prepend IV
                javax.crypto.CipherOutputStream(fos, cipher).use { cos ->
                    fis.copyTo(cos)
                }
            }
        }
        
        return Base64.encodeToString(fileKey, Base64.NO_WRAP)
    }

    /**
     * Decrypts a file using the provided Base64 AES key.
     * Reads the IV from the first 12 bytes of the input file.
     */
    fun decryptFile(inputFile: File, outputFile: File, fileKeyBase64: String) {
        val fileKey = Base64.decode(fileKeyBase64, Base64.NO_WRAP)
        
        java.io.FileInputStream(inputFile).use { fis ->
            val iv = ByteArray(12)
            if (fis.read(iv) != 12) throw IllegalStateException("Invalid encrypted file format")
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))
            
            javax.crypto.CipherInputStream(fis, cipher).use { cis ->
                java.io.FileOutputStream(outputFile).use { fos ->
                    cis.copyTo(fos)
                }
            }
        }
    }
    
    /**
     * Wipes the identity/DB keys on logout: deletes the Keystore master key alias,
     * clears the encrypted key blobs from SharedPreferences, and resets in-memory caches.
     */
    @Synchronized
    fun resetKeys(context: Context) {
        if (!this::appContext.isInitialized) {
            init(context)
        }
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        prefs.edit().clear().apply()
        cachedKeyPair = null
        cachedDbKey = null
        synchronized(authKeyCache) { authKeyCache.clear() }
    }

    /**
     * Encrypts a plaintext string (e.g. a cached profile field) using the same
     * Keystore-wrapped AES master key as everything else in this class, so callers
     * don't need to manage their own key for a simple SharedPreferences-encryption need.
     */
    fun encryptForPrefs(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrGenerateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    /** Decrypts a value produced by [encryptForPrefs]. Returns null if it can't be decrypted (e.g. wrong/rotated key). */
    fun decryptForPrefs(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = combined.sliceArray(0 until 12)
            val ciphertext = combined.sliceArray(12 until combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrGenerateMasterKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun copyUriToCache(context: android.content.Context, uri: android.net.Uri, prefix: String): File? {
        return try {
            val file = File(context.cacheDir, "$prefix${java.util.UUID.randomUUID()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
