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

//! Executes the unchanged core/js planner in the repository's QuickJS engine.
use serde_json::Value;
use std::ffi::{CStr, CString, c_char, c_int};

const SOURCES: &[(&str, &str)] = &[
    ("core/js/shared/trackAnalysis.js", include_str!("../../../core/js/shared/trackAnalysis.js")),
    ("crates/orchard-transition-planner/js/index.js", include_str!("../js/index.js")),
    ("core/js/src/audio/crossfade/transitionPlanner.js", include_str!("../../../core/js/src/audio/crossfade/transitionPlanner.js")),
    ("core/js/src/audio/crossfade/wsolaPlanner.js", include_str!("../../../core/js/src/audio/crossfade/wsolaPlanner.js")),
    ("core/js/src/audio/crossfade/pairTransitionPlanner.js", include_str!("../../../core/js/src/audio/crossfade/pairTransitionPlanner.js")),
    ("core/js/src/audio/crossfade/pairTransitionEvidence.js", include_str!("../../../core/js/src/audio/crossfade/pairTransitionEvidence.js")),
    ("core/js/src/audio/crossfade/pairTransitionGates.js", include_str!("../../../core/js/src/audio/crossfade/pairTransitionGates.js")),
    ("core/js/src/audio/crossfade/pairTransitionScoring.js", include_str!("../../../core/js/src/audio/crossfade/pairTransitionScoring.js")),
    ("core/js/src/audio/crossfade/pairTransitionFallback.js", include_str!("../../../core/js/src/audio/crossfade/pairTransitionFallback.js")),
    ("core/js/src/audio/crossfade/pairTempoHarmony.js", include_str!("../../../core/js/src/audio/crossfade/pairTempoHarmony.js")),
    ("core/js/src/audio/crossfade/transitionFlow.js", include_str!("../../../core/js/src/audio/crossfade/transitionFlow.js")),
    ("core/js/src/audio/crossfade/outgoingLoop.js", include_str!("../../../core/js/src/audio/crossfade/outgoingLoop.js")),
    ("core/js/src/audio/crossfade/transitionChoreography.js", include_str!("../../../core/js/src/audio/crossfade/transitionChoreography.js")),
    ("core/js/src/audio/crossfade/transitionPolicy.js", include_str!("../../../core/js/src/audio/crossfade/transitionPolicy.js")),
];

unsafe extern "C" {
    fn orchard_planner_invoke(method: *const c_char, input: *const c_char,
        names: *const *const c_char, sources: *const *const c_char,
        count: usize, success: *mut c_int) -> *mut c_char;
    fn orchard_planner_free(value: *mut c_char);
    fn orchard_planner_session_new(names: *const *const c_char,
        sources: *const *const c_char, count: usize) -> *mut std::ffi::c_void;
    fn orchard_planner_session_invoke(session: *mut std::ffi::c_void,
        method: *const c_char, input: *const c_char, success: *mut c_int) -> *mut c_char;
    fn orchard_planner_session_free(session: *mut std::ffi::c_void);
}

/// Reuses a bounded planner context for the duration of one queue sort.
pub struct PlannerSession {
    raw: *mut std::ffi::c_void,
    _names: Vec<CString>,
    _sources: Vec<CString>,
    _name_ptrs: Vec<*const c_char>,
    _source_ptrs: Vec<*const c_char>,
}

impl PlannerSession {
    pub fn new() -> Result<Self, String> {
        let names: Vec<_> = SOURCES.iter().map(|(name, _)| CString::new(*name).unwrap()).collect();
        let sources: Vec<_> = SOURCES.iter().map(|(_, source)| CString::new(*source).unwrap()).collect();
        let name_ptrs: Vec<_> = names.iter().map(|s| s.as_ptr()).collect();
        let source_ptrs: Vec<_> = sources.iter().map(|s| s.as_ptr()).collect();
        let raw = unsafe { orchard_planner_session_new(name_ptrs.as_ptr(), source_ptrs.as_ptr(), SOURCES.len()) };
        if raw.is_null() { return Err("Could not initialize the shared planner".into()); }
        Ok(Self { raw, _names: names, _sources: sources,
            _name_ptrs: name_ptrs, _source_ptrs: source_ptrs })
    }

    pub fn invoke(&mut self, method: &str, input: &Value) -> Result<Value, String> {
        if !matches!(method, "native" | "live") { return Err("Unknown shared planner method".into()); }
        let method = CString::new(method).map_err(|e| e.to_string())?;
        let input = CString::new(input.to_string()).map_err(|e| e.to_string())?;
        let mut success = 0;
        let result = unsafe { orchard_planner_session_invoke(self.raw, method.as_ptr(), input.as_ptr(), &mut success) };
        if result.is_null() { return Err("QuickJS planner allocation failed".into()); }
        let text = unsafe { CStr::from_ptr(result) }.to_string_lossy().into_owned();
        unsafe { orchard_planner_free(result) };
        if success == 0 { return Err(format!("QuickJS planner: {text}")); }
        serde_json::from_str(&text).map_err(|e| format!("Invalid shared planner result: {e}"))
    }
}

impl Drop for PlannerSession {
    fn drop(&mut self) { unsafe { orchard_planner_session_free(self.raw) }; }
}

/// JSON in, JSON out through the JS entry. No Rust musical policy.
pub fn invoke(method: &str, input: &Value) -> Result<Value, String> {
    if !matches!(method, "native" | "live") {
        return Err("Unknown shared planner method".into());
    }
    let method = CString::new(method).map_err(|e| e.to_string())?;
    let input = CString::new(input.to_string()).map_err(|e| e.to_string())?;
    let names: Vec<_> = SOURCES.iter().map(|(name, _)| CString::new(*name).unwrap()).collect();
    let sources: Vec<_> = SOURCES.iter().map(|(_, source)| CString::new(*source).unwrap()).collect();
    let names: Vec<_> = names.iter().map(|s| s.as_ptr()).collect();
    let source_ptrs: Vec<_> = sources.iter().map(|s| s.as_ptr()).collect();
    let mut success = 0;
    // All inputs outlive the synchronous C call. Only its owned result crosses back.
    let result = unsafe { orchard_planner_invoke(method.as_ptr(), input.as_ptr(),
        names.as_ptr(), source_ptrs.as_ptr(), SOURCES.len(), &mut success) };
    if result.is_null() { return Err("QuickJS planner allocation failed".into()); }
    let text = unsafe { CStr::from_ptr(result) }.to_string_lossy().into_owned();
    unsafe { orchard_planner_free(result) };
    if success == 0 { return Err(format!("QuickJS planner: {text}")); }
    serde_json::from_str(&text).map_err(|e| format!("Invalid shared planner result: {e}"))
}
