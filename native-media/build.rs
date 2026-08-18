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

fn main() {
    // Read the *target* rather than a `cfg!`, which in a build script reports the
    // host and would silently do the wrong thing on every cross-compile. This is
    // the bug souvlaki's own build.rs has: it gates its MediaPlayer.framework
    // link on `#[cfg(target_os = "macos")]`, so cross-building macOS from Linux
    // never emits the directive.
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();

    if target_os == "windows" {
        // napi-build's windows branch keys off CARGO_CFG_TARGET_ENV, which is
        // "gnu" for the windows-gnullvm target we cross-compile to. That path
        // panics looking for libnode.dll, a Node.js artifact Electron does not
        // ship. We need nothing from it anyway: napi-sys resolves every napi_*
        // symbol at runtime with GetProcAddress against the host executable, so
        // there is no import library to link in the first place.
        return;
    }

    napi_build::setup();

    if target_os == "macos" {
        println!("cargo:rustc-link-lib=framework=MediaPlayer");
        println!("cargo:rustc-link-lib=framework=AppKit");
        println!("cargo:rustc-link-lib=framework=Foundation");
    }
}
