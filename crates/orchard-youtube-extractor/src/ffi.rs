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

use std::ffi::{CString, c_char};

/// Parse and extract a player without retaining the borrowed source.
/// Returns owned JSON, including an `error` field on failure.
///
/// # Safety
/// `source` must point to `length` readable bytes for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_youtube_extract(source: *const u8, length: usize) -> *mut c_char {
    let result = std::panic::catch_unwind(|| {
        if source.is_null() || length > 16 * 1024 * 1024 {
            return Err("Invalid player source length".to_owned());
        }
        let bytes = unsafe { std::slice::from_raw_parts(source, length) };
        let text =
            std::str::from_utf8(bytes).map_err(|_| "Player source must be UTF-8".to_owned())?;
        crate::extract(text)
    })
    .unwrap_or_else(|_| Err("Native player extraction failed".to_owned()));
    let json = match result {
        Ok(extraction) => serde_json::to_string(&extraction),
        Err(error) => serde_json::to_string(&serde_json::json!({ "error": error })),
    };
    json.ok()
        .and_then(|json| CString::new(json).ok())
        .map_or(std::ptr::null_mut(), CString::into_raw)
}

/// # Safety
/// `result` must be null or a live pointer returned by orchard_youtube_extract,
/// and must be released exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_youtube_extract_free(result: *mut c_char) {
    if !result.is_null() {
        drop(unsafe { CString::from_raw(result) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ffi_rejects_invalid_inputs_and_releases_owned_results() {
        for (ptr, len) in [
            (std::ptr::null(), 1),
            (b"x".as_ptr(), usize::MAX),
            ([255u8].as_ptr(), 1),
        ] {
            let result = unsafe { orchard_youtube_extract(ptr, len) };
            assert!(!result.is_null());
            let value: serde_json::Value =
                serde_json::from_slice(unsafe { std::ffi::CStr::from_ptr(result).to_bytes() })
                    .unwrap();
            assert!(value["error"].is_string());
            unsafe {
                orchard_youtube_extract_free(result);
            }
        }
        unsafe {
            orchard_youtube_extract_free(std::ptr::null_mut());
        }
    }
}
