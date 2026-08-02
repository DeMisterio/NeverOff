mod ecdh;
mod encryption;
mod kdf;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jbyteArray, jint, jobjectArray, jstring};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};

/// JNI: Derive ECDH shared secret
/// Takes local private key (PKCS8 DER) and remote public key (X509/SEC1 DER) as byte arrays.
#[no_mangle]
pub extern "system" fn Java_com_neveroff_crypto_CryptoNative_ecdhDerive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    local_private_key: JByteArray<'local>,
    remote_public_key: JByteArray<'local>,
) -> jbyteArray {
    let result = (|| -> Result<Vec<u8>, String> {
        let priv_bytes = env.convert_byte_array(&local_private_key)
            .map_err(|e| format!("Failed to read private key: {}", e))?;
        let pub_bytes = env.convert_byte_array(&remote_public_key)
            .map_err(|e| format!("Failed to read public key: {}", e))?;
        
        ecdh::derive_shared_secret(&priv_bytes, &pub_bytes)
    })();

    match result {
        Ok(secret) => {
            env.byte_array_from_slice(&secret)
                .unwrap_or_else(|_| JByteArray::default())
                .into_raw()
        }
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", e);
            JByteArray::default().into_raw()
        }
    }
}

/// JNI: AES-GCM Encrypt
/// Returns IV (12 bytes) + ciphertext concatenated.
#[no_mangle]
pub extern "system" fn Java_com_neveroff_crypto_CryptoNative_aesGcmEncrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    key: JByteArray<'local>,
) -> jbyteArray {
    let result = (|| -> Result<Vec<u8>, String> {
        let plain_bytes = env.convert_byte_array(&plaintext)
            .map_err(|e| format!("Failed to read plaintext: {}", e))?;
        let key_bytes = env.convert_byte_array(&key)
            .map_err(|e| format!("Failed to read key: {}", e))?;
        
        encryption::aes_gcm_encrypt(&plain_bytes, &key_bytes)
    })();

    match result {
        Ok(encrypted) => {
            env.byte_array_from_slice(&encrypted)
                .unwrap_or_else(|_| JByteArray::default())
                .into_raw()
        }
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", e);
            JByteArray::default().into_raw()
        }
    }
}

/// JNI: AES-GCM Decrypt
/// Takes IV (12 bytes) + ciphertext concatenated.
#[no_mangle]
pub extern "system" fn Java_com_neveroff_crypto_CryptoNative_aesGcmDecrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    combined: JByteArray<'local>,
    key: JByteArray<'local>,
) -> jbyteArray {
    let result = (|| -> Result<Vec<u8>, String> {
        let combined_bytes = env.convert_byte_array(&combined)
            .map_err(|e| format!("Failed to read combined: {}", e))?;
        let key_bytes = env.convert_byte_array(&key)
            .map_err(|e| format!("Failed to read key: {}", e))?;
        
        encryption::aes_gcm_decrypt(&combined_bytes, &key_bytes)
    })();

    match result {
        Ok(decrypted) => {
            env.byte_array_from_slice(&decrypted)
                .unwrap_or_else(|_| JByteArray::default())
                .into_raw()
        }
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", e);
            JByteArray::default().into_raw()
        }
    }
}

/// JNI: Derive AES key from auth_key and msg_key using HMAC-SHA256
#[no_mangle]
pub extern "system" fn Java_com_neveroff_crypto_CryptoNative_deriveAesKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    auth_key: JByteArray<'local>,
    msg_key: JByteArray<'local>,
) -> jbyteArray {
    let result = (|| -> Result<Vec<u8>, String> {
        let auth_bytes = env.convert_byte_array(&auth_key)
            .map_err(|e| format!("Failed to read auth_key: {}", e))?;
        let msg_bytes = env.convert_byte_array(&msg_key)
            .map_err(|e| format!("Failed to read msg_key: {}", e))?;
        
        Ok(kdf::derive_aes_key(&auth_bytes, &msg_bytes))
    })();

    match result {
        Ok(key) => {
            env.byte_array_from_slice(&key)
                .unwrap_or_else(|_| JByteArray::default())
                .into_raw()
        }
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", e);
            JByteArray::default().into_raw()
        }
    }
}

/// JNI: Batch decrypt multiple messages at once.
/// Takes arrays of: ciphertexts (Base64), msg_keys (Base64), and a single auth_key.
/// Returns array of decrypted strings.
#[no_mangle]
pub extern "system" fn Java_com_neveroff_crypto_CryptoNative_batchDecrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ciphertexts_b64: JObjectArray<'local>,
    msg_keys_b64: JObjectArray<'local>,
    auth_key: JByteArray<'local>,
) -> jobjectArray {
    let result = (|| -> Result<Vec<String>, String> {
        let auth_bytes = env.convert_byte_array(&auth_key)
            .map_err(|e| format!("Failed to read auth_key: {}", e))?;
        
        let count = env.get_array_length(&ciphertexts_b64)
            .map_err(|e| format!("Failed to get array length: {}", e))? as usize;
        
        let mut results = Vec::with_capacity(count);
        
        for i in 0..count {
            let ct_jstr: JString = env.get_object_array_element(&ciphertexts_b64, i as jint)
                .map_err(|e| format!("Failed to get ciphertext[{}]: {}", i, e))?
                .into();
            let mk_jstr: JString = env.get_object_array_element(&msg_keys_b64, i as jint)
                .map_err(|e| format!("Failed to get msg_key[{}]: {}", i, e))?
                .into();
            
            let ct_str: String = env.get_string(&ct_jstr)
                .map_err(|e| format!("Failed to convert ciphertext string: {}", e))?
                .into();
            let mk_str: String = env.get_string(&mk_jstr)
                .map_err(|e| format!("Failed to convert msg_key string: {}", e))?
                .into();
            
            let combined = BASE64.decode(&ct_str)
                .map_err(|e| format!("Base64 decode ciphertext failed: {}", e))?;
            let msg_key = BASE64.decode(&mk_str)
                .map_err(|e| format!("Base64 decode msg_key failed: {}", e))?;
            
            let aes_key = kdf::derive_aes_key(&auth_bytes, &msg_key);
            
            match encryption::aes_gcm_decrypt(&combined, &aes_key) {
                Ok(plaintext) => {
                    let text = String::from_utf8(plaintext)
                        .unwrap_or_else(|_| "[Decoding error]".to_string());
                    results.push(text);
                }
                Err(_) => {
                    results.push("[Encrypted message]".to_string());
                }
            }
        }
        
        Ok(results)
    })();

    match result {
        Ok(texts) => {
            let string_class = env.find_class("java/lang/String").unwrap();
            let empty = env.new_string("").unwrap();
            let arr = env.new_object_array(texts.len() as jint, &string_class, &empty).unwrap();
            
            for (i, text) in texts.iter().enumerate() {
                let jstr = env.new_string(text).unwrap();
                let _ = env.set_object_array_element(&arr, i as jint, &jstr);
            }
            
            arr.into_raw()
        }
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", e);
            std::ptr::null_mut()
        }
    }
}
