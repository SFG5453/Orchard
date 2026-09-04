use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use serde_json::Value;

const MAX_VALUE_BYTES: usize = 6 * 1024 * 1024;

pub struct SessionState {
    path: PathBuf,
    values: Mutex<BTreeMap<String, Value>>,
}

impl SessionState {
    pub fn open(path: PathBuf) -> Self {
        Self {
            values: Mutex::new(read_state(&path)),
            path,
        }
    }

    pub fn all(&self) -> Result<BTreeMap<String, Value>, String> {
        self.values
            .lock()
            .map(|values| values.clone())
            .map_err(lock_error)
    }

    pub fn set(&self, key: String, value: Value) -> Result<bool, String> {
        if key.is_empty() {
            return Ok(false);
        }
        if serde_json::to_vec(&value)
            .map_err(|error| error.to_string())?
            .len()
            > MAX_VALUE_BYTES
        {
            return Ok(false);
        }

        let mut values = self.values.lock().map_err(lock_error)?;
        if values.get(&key) == Some(&value) {
            return Ok(true);
        }

        let mut next = values.clone();
        next.insert(key, value);
        write_state(&self.path, &next).map_err(|error| error.to_string())?;
        *values = next;
        Ok(true)
    }
}

fn read_state(path: &Path) -> BTreeMap<String, Value> {
    fs::read(path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

fn write_state(path: &Path, values: &BTreeMap<String, Value>) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension(format!("{}.tmp", std::process::id()));
    let result = (|| {
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .mode(0o600)
            .open(&temporary)?;
        serde_json::to_writer(&mut file, values)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn lock_error<T>(error: std::sync::PoisonError<T>) -> String {
    format!("session state is poisoned: {error}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch_path(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "orchard-linux-session-{}-{name}.json",
            std::process::id()
        ))
    }

    #[test]
    fn missing_or_corrupt_state_opens_empty() {
        let path = scratch_path("corrupt");
        let _ = fs::remove_file(&path);
        assert!(read_state(&path).is_empty());
        fs::write(&path, b"{\"truncated\":").unwrap();
        assert!(read_state(&path).is_empty());
        let _ = fs::remove_file(path);
    }

    #[test]
    fn values_are_written_atomically_and_restored() {
        let path = scratch_path("round-trip");
        let _ = fs::remove_file(&path);
        let store = SessionState::open(path.clone());
        assert!(
            store
                .set(
                    "orchard:last-page".into(),
                    serde_json::json!({ "view": "home" })
                )
                .unwrap()
        );

        let restored = SessionState::open(path.clone());
        assert_eq!(
            restored.all().unwrap().get("orchard:last-page"),
            Some(&serde_json::json!({ "view": "home" }))
        );
        assert!(
            !path
                .with_extension(format!("{}.tmp", std::process::id()))
                .exists()
        );
        let _ = fs::remove_file(path);
    }

    #[test]
    fn invalid_keys_and_oversized_values_are_refused() {
        let path = scratch_path("limits");
        let _ = fs::remove_file(&path);
        let store = SessionState::open(path.clone());
        assert!(!store.set(String::new(), Value::Null).unwrap());
        assert!(
            !store
                .set(
                    "orchard:huge".into(),
                    Value::String("x".repeat(MAX_VALUE_BYTES + 1))
                )
                .unwrap()
        );
        assert!(store.all().unwrap().is_empty());
        let _ = fs::remove_file(path);
    }
}
