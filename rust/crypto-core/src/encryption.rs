use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use rand::RngCore;

/// Encrypt plaintext using AES-256-GCM.
/// Returns IV (12 bytes) + ciphertext concatenated.
pub fn aes_gcm_encrypt(plaintext: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    if key.len() != 32 {
        return Err(format!("Key must be 32 bytes, got {}", key.len()));
    }

    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| format!("Failed to create cipher: {}", e))?;

    let mut iv = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut iv);
    let nonce = Nonce::from_slice(&iv);

    let ciphertext = cipher.encrypt(nonce, plaintext)
        .map_err(|e| format!("Encryption failed: {}", e))?;

    // Combine IV + ciphertext
    let mut combined = Vec::with_capacity(12 + ciphertext.len());
    combined.extend_from_slice(&iv);
    combined.extend_from_slice(&ciphertext);

    Ok(combined)
}

/// Decrypt ciphertext using AES-256-GCM.
/// Input is IV (12 bytes) + ciphertext concatenated.
pub fn aes_gcm_decrypt(combined: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    if key.len() != 32 {
        return Err(format!("Key must be 32 bytes, got {}", key.len()));
    }
    if combined.len() < 12 {
        return Err("Combined data too short (must be at least 12 bytes for IV)".to_string());
    }

    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| format!("Failed to create cipher: {}", e))?;

    let (iv_bytes, ciphertext) = combined.split_at(12);
    let nonce = Nonce::from_slice(iv_bytes);

    let plaintext = cipher.decrypt(nonce, ciphertext)
        .map_err(|e| format!("Decryption failed: {}", e))?;

    Ok(plaintext)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = [0x42u8; 32];
        let plaintext = b"Hello, NeverOff!";

        let encrypted = aes_gcm_encrypt(plaintext, &key).unwrap();
        assert!(encrypted.len() > 12 + plaintext.len()); // IV + ciphertext + tag

        let decrypted = aes_gcm_decrypt(&encrypted, &key).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_wrong_key_fails() {
        let key1 = [0x42u8; 32];
        let key2 = [0x43u8; 32];
        let plaintext = b"Secret";

        let encrypted = aes_gcm_encrypt(plaintext, &key1).unwrap();
        let result = aes_gcm_decrypt(&encrypted, &key2);
        assert!(result.is_err());
    }

    #[test]
    fn test_empty_plaintext() {
        let key = [0x42u8; 32];
        let plaintext = b"";

        let encrypted = aes_gcm_encrypt(plaintext, &key).unwrap();
        let decrypted = aes_gcm_decrypt(&encrypted, &key).unwrap();
        assert_eq!(decrypted, plaintext);
    }
}
