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

use serde_json::Value;
use std::process::Command;

fn compare(actual: &Value, expected: &Value, path: &str) {
    match (actual, expected) {
        (Value::Number(a), Value::Number(b)) => {
            let (a, b) = (a.as_f64().unwrap(), b.as_f64().unwrap());
            assert!((a - b).abs() <= 1e-9 * b.abs().max(1.0), "{path}: {a} != {b}");
        }
        (Value::Array(a), Value::Array(b)) => {
            assert_eq!(a.len(), b.len(), "{path}");
            for (i, (a, b)) in a.iter().zip(b).enumerate() { compare(a, b, &format!("{path}[{i}]")); }
        }
        (Value::Object(a), Value::Object(b)) => {
            assert_eq!(a.len(), b.len(), "{path}");
            for (key, b) in b { compare(a.get(key).unwrap_or_else(|| panic!("{path}.{key} missing")), b, &format!("{path}.{key}")); }
        }
        _ => assert_eq!(actual, expected, "{path}"),
    }
}

#[test]
fn quickjs_matches_mobile_entry_on_all_mobile_fixtures() {
    let output = Command::new("node").arg(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/node-reference.mjs"))
        .output().expect("Node is needed for the independent planner parity reference");
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    let cases: Vec<Value> = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(cases.len(), 264);
    for case in cases {
        let method = case["method"].as_str().unwrap();
        let result = orchard_transition_planner::invoke(method, &case["input"])
            .unwrap_or_else(|e| panic!("{} {method}: {e}", case["name"]));
        compare(&result, &case["expected"], &format!("{} {method}", case["name"]));
    }
}
