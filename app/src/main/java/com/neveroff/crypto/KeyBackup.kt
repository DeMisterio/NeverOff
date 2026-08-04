package com.neveroff.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-protected escrow of the identity private key.
 *
 * Without this, the EC-P256 key that reads a user's entire message history
 * exists on exactly one device and nowhere else. Uninstalling the app or losing
 * the phone destroyed it, and every message ever sent or received became
 * permanently unreadable — including the user's own, since ECDH is symmetric
 * and one key reads both sides of a conversation.
 *
 * The message ciphertext was never lost; it is still in `messages` on the
 * server. So this is a key-availability problem, and key escrow is the fix.
 *
 * What the server ends up holding is the private key sealed with AES-256-GCM
 * under a key derived from a passphrase it never sees. A dump of the backup
 * table yields ciphertexts and salts, nothing more: an attacker must run the
 * KDF once per guess per user, which is what the iteration count is for.
 *
 * The trade this makes, deliberately and unavoidably: a forgotten passphrase
 * cannot be reset by anyone, including us. A passphrase we could reset would be
 * one an attacker could reset, and the escrow would be theatre.
 */
object KeyBackup {

    /**
     * OWASP's floor for PBKDF2-HMAC-SHA256 at time of writing.
     *
     * Written into each backup row rather than assumed by the client, because
     * this number only ever goes up, and a client that hardcodes today's value
     * cannot open a backup written under tomorrow's.
     */
    const val DEFAULT_ITERATIONS = 210_000
    const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Everything needed to reconstruct the key, and nothing that reveals it. */
    data class Blob(
        val publicKeyBase64: String,
        val wrappedPrivateKeyBase64: String,
        val saltBase64: String,
        val iterations: Int,
        val algorithm: String
    )

    class WrongPassphrase : Exception("That passphrase does not open this backup.")

    /**
     * Minimum passphrase length.
     *
     * Low by password-policy standards on purpose. This guards a blob that is
     * already behind a per-user salt and 210k KDF rounds, and the realistic
     * failure mode here is not a weak passphrase — it is a forgotten one, which
     * costs the user everything. Complexity rules push people towards
     * unmemorable strings, and unmemorable is the actual threat.
     */
    const val MIN_PASSPHRASE_LENGTH = 8

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        algorithm: String
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(algorithm)
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            // The PBEKeySpec holds its own copy of the passphrase; clearing it
            // keeps the window in which it sits in the heap as short as we can
            // make it. Not a guarantee on a managed runtime, but free.
            spec.clearPassword()
        }
    }

    /**
     * Seal the current device's private key under [passphrase].
     *
     * Reads the key pair through CryptoManager rather than taking one as a
     * parameter, so there is exactly one place in the app that can produce a
     * backup and it is always the key actually in use.
     */
    fun create(passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): Blob {
        val keyPair = CryptoManager.getOrGenerateKeyPair()
        val random = SecureRandom()

        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val wrappingKey = deriveKey(passphrase, salt, iterations, ALGORITHM)

        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val sealed = cipher.doFinal(keyPair.private.encoded)

        return Blob(
            publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP),
            wrappedPrivateKeyBase64 = Base64.encodeToString(iv + sealed, Base64.NO_WRAP),
            saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            iterations = iterations,
            algorithm = ALGORITHM
        )
    }

    /**
     * Open [blob] with [passphrase] and install the key on this device.
     *
     * Throws [WrongPassphrase] when the passphrase is wrong. GCM authentication
     * catches that on its own in almost every case, but the recovered key is
     * checked against the backup's public half as well: a wrong passphrase that
     * somehow satisfied the tag would otherwise install a garbage key, and the
     * user would meet the failure much later as unreadable messages rather than
     * as "wrong passphrase" — which is the difference between an annoyance and
     * a bug report nobody can diagnose.
     */
    fun restore(blob: Blob, passphrase: CharArray) {
        val salt = Base64.decode(blob.saltBase64, Base64.NO_WRAP)
        val combined = Base64.decode(blob.wrappedPrivateKeyBase64, Base64.NO_WRAP)
        if (combined.size <= IV_BYTES) throw WrongPassphrase()

        val wrappingKey = deriveKey(passphrase, salt, blob.iterations, blob.algorithm)
        val iv = combined.copyOfRange(0, IV_BYTES)
        val sealed = combined.copyOfRange(IV_BYTES, combined.size)

        val privateKeyBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw WrongPassphrase()
        }

        val publicKeyBytes = Base64.decode(blob.publicKeyBase64, Base64.NO_WRAP)
        val derivedPublic = try {
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
                .encoded
        } catch (e: Exception) {
            throw WrongPassphrase()
        }
        if (!derivedPublic.contentEquals(publicKeyBytes)) throw WrongPassphrase()

        CryptoManager.installRestoredKeyPair(publicKeyBytes, privateKeyBytes)
    }
}
