use p256::{
    ecdh::EphemeralSecret,
    PublicKey, SecretKey,
    pkcs8::DecodePrivateKey,
};
use elliptic_curve::sec1::FromEncodedPoint;

/// Derive ECDH shared secret from a local private key (PKCS8 DER)
/// and a remote public key (X509/SEC1 DER).
pub fn derive_shared_secret(
    local_private_key_der: &[u8],
    remote_public_key_der: &[u8],
) -> Result<Vec<u8>, String> {
    // Parse the private key from PKCS8 DER
    let secret_key = SecretKey::from_pkcs8_der(local_private_key_der)
        .map_err(|e| format!("Failed to parse private key: {}", e))?;
    
    // Parse the remote public key from X509/SEC1 DER
    // Android exports public keys in X509 SubjectPublicKeyInfo format
    let public_key = parse_public_key(remote_public_key_der)?;
    
    // Perform ECDH
    let shared_secret = p256::ecdh::diffie_hellman(
        secret_key.to_nonzero_scalar(),
        public_key.as_affine(),
    );
    
    Ok(shared_secret.raw_secret_bytes().to_vec())
}

/// Parse a public key from either X509 SubjectPublicKeyInfo DER or raw SEC1 format
fn parse_public_key(der: &[u8]) -> Result<PublicKey, String> {
    // Try X509 SubjectPublicKeyInfo format first (what Java's getEncoded() produces)
    if der.len() > 26 {
        // X509 SubjectPublicKeyInfo for P-256 has a 26-byte header
        // The actual EC point starts after the header
        use p256::pkcs8::DecodePublicKey;
        if let Ok(pk) = PublicKey::from_public_key_der(der) {
            return Ok(pk);
        }
    }
    
    // Try raw SEC1 uncompressed point (65 bytes for P-256: 0x04 + 32 + 32)
    if der.len() == 65 && der[0] == 0x04 {
        let point = p256::EncodedPoint::from_bytes(der)
            .map_err(|e| format!("Invalid SEC1 point: {}", e))?;
        let pk = PublicKey::from_encoded_point(&point);
        if pk.is_some().into() {
            return Ok(pk.unwrap());
        }
    }
    
    Err(format!("Failed to parse public key (len={})", der.len()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::SecretKey;
    use rand::rngs::OsRng;
    
    #[test]
    fn test_ecdh_roundtrip() {
        // Generate two key pairs
        let sk1 = SecretKey::random(&mut OsRng);
        let sk2 = SecretKey::random(&mut OsRng);
        
        let pk1 = sk1.public_key();
        let pk2 = sk2.public_key();
        
        // Export to DER
        use p256::pkcs8::EncodePrivateKey;
        use p256::pkcs8::EncodePublicKey;
        
        let sk1_der = sk1.to_pkcs8_der().unwrap();
        let pk2_der = pk2.to_public_key_der().unwrap();
        
        let sk2_der = sk2.to_pkcs8_der().unwrap();
        let pk1_der = pk1.to_public_key_der().unwrap();
        
        // Derive shared secrets
        let secret1 = derive_shared_secret(sk1_der.as_bytes(), pk2_der.as_ref()).unwrap();
        let secret2 = derive_shared_secret(sk2_der.as_bytes(), pk1_der.as_ref()).unwrap();
        
        assert_eq!(secret1, secret2);
        assert_eq!(secret1.len(), 32); // P-256 shared secret is 32 bytes
    }
}
