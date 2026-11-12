/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

use std::process::Command;

// ORT copies onnxruntime and Dawn beside the worker; load them from there.
fn main() {
    match std::env::var("CARGO_CFG_TARGET_OS").as_deref() {
        Ok("linux") => println!("cargo:rustc-link-arg=-Wl,-rpath,$ORIGIN"),
        Ok("macos") => link_clang_runtime(),
        _ => {}
    }
}

// Rust links with -nodefaultlibs, and ORT's Objective-C availability checks need
// __isPlatformVersionAtLeast from clang's runtime.
fn link_clang_runtime() {
    let Ok(output) = Command::new("clang").arg("--print-resource-dir").output() else {
        return;
    };
    let resource_dir = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let library_dir = std::path::Path::new(&resource_dir).join("lib/darwin");
    if library_dir.join("libclang_rt.osx.a").exists() {
        println!("cargo:rustc-link-search=native={}", library_dir.display());
        println!("cargo:rustc-link-lib=static=clang_rt.osx");
    }
}
