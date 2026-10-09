/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

//! Key derivation and AES for segmented provider streams (Qobuz CMAF).
//! Web Crypto is absent from QuickJS, so the provider host calls these.

use aes::Aes128;
use aes::cipher::block_padding::Pkcs7;
use aes::cipher::{BlockDecryptMut, KeyIvInit, StreamCipher};
use hkdf::Hkdf;
use sha2::Sha256;
use std::slice;

type Aes128CbcDec = cbc::Decryptor<Aes128>;
type Aes128Ctr = ctr::Ctr128BE<Aes128>;

/// HKDF output is capped well below RFC 5869's 255 * 32 limit.
const MAX_HKDF_OUTPUT: usize = 1024;

unsafe fn bytes<'a>(data: *const u8, length: usize) -> &'a [u8] {
    if length == 0 { &[] } else { unsafe { slice::from_raw_parts(data, length) } }
}

/// Fills `out` with HKDF-SHA256 output. Returns 1 on success.
///
/// # Safety
/// Every pointer must reference the stated number of bytes; `out` must be writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_hkdf_sha256(
    key: *const u8,
    key_length: usize,
    salt: *const u8,
    salt_length: usize,
    info: *const u8,
    info_length: usize,
    out: *mut u8,
    out_length: usize,
) -> u8 {
    if out.is_null() || out_length == 0 || out_length > MAX_HKDF_OUTPUT
        || (key.is_null() && key_length > 0)
        || (salt.is_null() && salt_length > 0)
        || (info.is_null() && info_length > 0)
    {
        return 0;
    }
    let (key, salt, info) = unsafe {
        (bytes(key, key_length), bytes(salt, salt_length), bytes(info, info_length))
    };
    let output = unsafe { slice::from_raw_parts_mut(out, out_length) };
    // An empty salt means HashLen zeros, as in Node's hkdfSync and RFC 5869.
    let derived = Hkdf::<Sha256>::new((!salt.is_empty()).then_some(salt), key);
    u8::from(derived.expand(info, output).is_ok())
}

/// Decrypts AES-128-CBC with PKCS#7 padding into `out`, which must hold
/// `length` bytes. Stores the unpadded length in `written`. Returns 1 on success.
///
/// # Safety
/// `key` and `iv` must reference 16 bytes, `data` `length` bytes, and `out`
/// `length` writable bytes; `written` must be writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_aes128_cbc_decrypt(
    key: *const u8,
    iv: *const u8,
    data: *const u8,
    length: usize,
    out: *mut u8,
    written: *mut usize,
) -> u8 {
    if key.is_null() || iv.is_null() || data.is_null() || out.is_null() || written.is_null()
        || length == 0 || length % 16 != 0
    {
        return 0;
    }
    let (key, iv, data) = unsafe { (bytes(key, 16), bytes(iv, 16), bytes(data, length)) };
    let output = unsafe { slice::from_raw_parts_mut(out, length) };
    let Ok(cipher) = Aes128CbcDec::new_from_slices(key, iv) else { return 0 };
    match cipher.decrypt_padded_b2b_mut::<Pkcs7>(data, output) {
        Ok(plain) => {
            unsafe { *written = plain.len() };
            1
        }
        Err(_) => 0,
    }
}

/// Applies AES-128-CTR with a 128-bit big-endian counter in place.
/// Returns 1 on success.
///
/// # Safety
/// `key` and `iv` must reference 16 bytes and `data` `length` writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_aes128_ctr_apply(
    key: *const u8,
    iv: *const u8,
    data: *mut u8,
    length: usize,
) -> u8 {
    if key.is_null() || iv.is_null() || (data.is_null() && length > 0) {
        return 0;
    }
    if length == 0 {
        return 1;
    }
    let (key, iv) = unsafe { (bytes(key, 16), bytes(iv, 16)) };
    let Ok(mut cipher) = Aes128Ctr::new_from_slices(key, iv) else { return 0 };
    cipher.apply_keystream(unsafe { slice::from_raw_parts_mut(data, length) });
    1
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(value: &str) -> Vec<u8> {
        (0..value.len()).step_by(2).map(|i| u8::from_str_radix(&value[i..i + 2], 16).unwrap()).collect()
    }

    #[test]
    fn hkdf_matches_rfc5869_case_1() {
        let key = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        let salt = hex("000102030405060708090a0b0c");
        let info = hex("f0f1f2f3f4f5f6f7f8f9");
        let mut out = [0u8; 42];
        let ok = unsafe {
            orchard_hkdf_sha256(key.as_ptr(), key.len(), salt.as_ptr(), salt.len(),
                                info.as_ptr(), info.len(), out.as_mut_ptr(), out.len())
        };
        assert_eq!(ok, 1);
        assert_eq!(out.to_vec(), hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"));
    }

    #[test]
    fn ctr_matches_nist_sp800_38a() {
        let key = hex("2b7e151628aed2a6abf7158809cf4f3c");
        let iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
        let mut data = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51");
        let ok = unsafe { orchard_aes128_ctr_apply(key.as_ptr(), iv.as_ptr(), data.as_mut_ptr(), data.len()) };
        assert_eq!(ok, 1);
        assert_eq!(data, hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff"));
    }

    #[test]
    fn cbc_decrypts_and_strips_padding() {
        use aes::cipher::BlockEncryptMut;
        let key = hex("2b7e151628aed2a6abf7158809cf4f3c");
        let iv = hex("000102030405060708090a0b0c0d0e0f");
        let plain = hex("6bc1bee22e409f96e93d7e117393172a");
        let mut buffer = [0u8; 32];
        buffer[..16].copy_from_slice(&plain);
        let cipher = cbc::Encryptor::<Aes128>::new_from_slices(&key, &iv).unwrap()
            .encrypt_padded_mut::<Pkcs7>(&mut buffer, 16).unwrap().to_vec();
        // NIST SP 800-38A F.2.1 block one, then a full PKCS#7 padding block.
        assert_eq!(&cipher[..16], hex("7649abac8119b246cee98e9b12e9197d").as_slice());
        let mut out = vec![0u8; cipher.len()];
        let mut written = 0usize;
        let ok = unsafe {
            orchard_aes128_cbc_decrypt(key.as_ptr(), iv.as_ptr(), cipher.as_ptr(), cipher.len(),
                                       out.as_mut_ptr(), &mut written)
        };
        assert_eq!(ok, 1);
        assert_eq!(&out[..written], hex("6bc1bee22e409f96e93d7e117393172a").as_slice());
    }
}
