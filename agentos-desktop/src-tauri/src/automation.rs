use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::State;
use uuid::Uuid;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AutomationPreferences {
    client_id: String,
    keep_awake: bool,
}

struct AutomationInner {
    preferences: AutomationPreferences,
    #[cfg(target_os = "macos")]
    caffeinate: Option<Child>,
}

pub struct AutomationRuntimeState {
    storage_file: PathBuf,
    inner: Mutex<AutomationInner>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AutomationRuntimeInfo {
    client_id: String,
    platform: String,
    app_version: String,
    keep_awake: bool,
    keep_awake_supported: bool,
}

impl AutomationRuntimeState {
    pub fn new(storage_file: PathBuf) -> Self {
        let preferences = fs::read_to_string(&storage_file)
            .ok()
            .and_then(|raw| serde_json::from_str::<AutomationPreferences>(&raw).ok())
            .filter(|value| !value.client_id.trim().is_empty())
            .unwrap_or_else(|| AutomationPreferences {
                client_id: Uuid::new_v4().to_string(),
                keep_awake: false,
            });
        let state = Self {
            storage_file,
            inner: Mutex::new(AutomationInner {
                preferences,
                #[cfg(target_os = "macos")]
                caffeinate: None,
            }),
        };
        let _ = state.persist();
        let restore = state
            .inner
            .lock()
            .map(|inner| inner.preferences.keep_awake)
            .unwrap_or(false);
        if restore && state.set_keep_awake(true).is_err() {
            if let Ok(mut inner) = state.inner.lock() {
                inner.preferences.keep_awake = false;
            }
            let _ = state.persist();
        }
        state
    }

    fn info(&self) -> Result<AutomationRuntimeInfo, String> {
        let inner = self
            .inner
            .lock()
            .map_err(|_| "自动化桌面状态不可用".to_string())?;
        Ok(AutomationRuntimeInfo {
            client_id: inner.preferences.client_id.clone(),
            platform: platform().to_string(),
            app_version: env!("CARGO_PKG_VERSION").to_string(),
            keep_awake: inner.preferences.keep_awake,
            keep_awake_supported: cfg!(any(target_os = "macos", target_os = "windows")),
        })
    }

    fn persist(&self) -> Result<(), String> {
        let inner = self
            .inner
            .lock()
            .map_err(|_| "自动化桌面状态不可用".to_string())?;
        let encoded = serde_json::to_vec_pretty(&inner.preferences)
            .map_err(|error| format!("无法编码自动化桌面设置：{error}"))?;
        fs::write(&self.storage_file, encoded)
            .map_err(|error| format!("无法保存自动化桌面设置：{error}"))
    }

    fn set_keep_awake(&self, enabled: bool) -> Result<(), String> {
        let mut inner = self
            .inner
            .lock()
            .map_err(|_| "自动化桌面状态不可用".to_string())?;
        apply_keep_awake(&mut inner, enabled)?;
        inner.preferences.keep_awake = enabled;
        drop(inner);
        self.persist()
    }

    fn release(&self) {
        if let Ok(mut inner) = self.inner.lock() {
            let _ = apply_keep_awake(&mut inner, false);
        }
    }
}

impl Drop for AutomationRuntimeState {
    fn drop(&mut self) {
        self.release();
    }
}

#[tauri::command]
pub fn automation_runtime_info(
    state: State<'_, AutomationRuntimeState>,
) -> Result<AutomationRuntimeInfo, String> {
    state.info()
}

#[tauri::command]
pub fn set_automation_keep_awake(
    state: State<'_, AutomationRuntimeState>,
    enabled: bool,
) -> Result<AutomationRuntimeInfo, String> {
    state.set_keep_awake(enabled)?;
    state.info()
}

fn platform() -> &'static str {
    #[cfg(target_os = "macos")]
    return "macos";
    #[cfg(target_os = "windows")]
    return "windows";
    #[cfg(target_os = "linux")]
    return "linux";
    #[allow(unreachable_code)]
    "unknown"
}

#[cfg(target_os = "macos")]
fn apply_keep_awake(inner: &mut AutomationInner, enabled: bool) -> Result<(), String> {
    if enabled {
        if inner
            .caffeinate
            .as_mut()
            .is_some_and(|child| child.try_wait().ok().flatten().is_none())
        {
            return Ok(());
        }
        let child = Command::new("/usr/bin/caffeinate")
            .arg("-i")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|error| format!("无法启动 macOS 保持唤醒：{error}"))?;
        inner.caffeinate = Some(child);
    } else if let Some(mut child) = inner.caffeinate.take() {
        let _ = child.kill();
        let _ = child.wait();
    }
    Ok(())
}

#[cfg(target_os = "windows")]
fn apply_keep_awake(_inner: &mut AutomationInner, enabled: bool) -> Result<(), String> {
    use windows_sys::Win32::System::Power::{
        SetThreadExecutionState, ES_CONTINUOUS, ES_SYSTEM_REQUIRED,
    };
    let flags = if enabled {
        ES_CONTINUOUS | ES_SYSTEM_REQUIRED
    } else {
        ES_CONTINUOUS
    };
    let result = unsafe { SetThreadExecutionState(flags) };
    if result == 0 {
        Err("Windows 拒绝设置保持唤醒状态".to_string())
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temporary_preferences_file() -> PathBuf {
        std::env::temp_dir().join(format!("agentos-automation-{}.json", Uuid::new_v4()))
    }

    #[test]
    fn creates_and_persists_a_stable_client_identity() {
        let path = temporary_preferences_file();
        let first = AutomationRuntimeState::new(path.clone());
        let first_info = first.info().expect("first runtime info");

        assert!(!first_info.client_id.is_empty());
        assert!(!first_info.keep_awake);
        drop(first);

        let restored = AutomationRuntimeState::new(path.clone());
        let restored_info = restored.info().expect("restored runtime info");
        assert_eq!(first_info.client_id, restored_info.client_id);
        assert!(!restored_info.keep_awake);

        drop(restored);
        let _ = fs::remove_file(path);
    }

    #[test]
    fn replaces_invalid_preferences_with_safe_defaults() {
        let path = temporary_preferences_file();
        fs::write(&path, r#"{"clientId":"","keepAwake":true}"#).expect("write invalid preferences");

        let state = AutomationRuntimeState::new(path.clone());
        let info = state.info().expect("runtime info");
        assert!(!info.client_id.is_empty());
        assert!(!info.keep_awake);

        drop(state);
        let _ = fs::remove_file(path);
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn apply_keep_awake(_inner: &mut AutomationInner, enabled: bool) -> Result<(), String> {
    if enabled {
        Err("当前平台暂不支持保持唤醒".to_string())
    } else {
        Ok(())
    }
}
