use hmac::{Hmac, Mac};
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

/// Derive a 256-bit AES key from auth_key and msg_key using HMAC-SHA256.
/// Matches the Kotlin implementation: HMAC-SHA256(auth_key, msg_key)[0..32]
pub fn derive_aes_key(auth_key: &[u8], msg_key: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(auth_key)
        .expect("HMAC can take key of any size");
    mac.update(msg_key);
    let result = mac.finalize().into_bytes();
    result[..32].to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_derive_aes_key_deterministic() {
        let auth_key = [0xAA; 32];
        let msg_key = [0xBB; 16];

        let key1 = derive_aes_key(&auth_key, &msg_key);
        let key2 = derive_aes_key(&auth_key, &msg_key);

        assert_eq!(key1, key2);
        assert_eq!(key1.len(), 32);
    }

    #[test]
    fn test_different_inputs_different_keys() {
        let auth_key = [0xAA; 32];
        let msg_key1 = [0xBB; 16];
        let msg_key2 = [0xCC; 16];

        let key1 = derive_aes_key(&auth_key, &msg_key1);
        let key2 = derive_aes_key(&auth_key, &msg_key2);

        assert_ne!(key1, key2);
    }
}
