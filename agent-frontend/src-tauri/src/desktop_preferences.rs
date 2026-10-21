//! 桌面端长期偏好：集中保存在 `~/.butvan-agent/desktop/`。
//!
//! 卸载、重装或第三方卸载工具只会清理 `~/Library/Application Support`、
//! `~/Library/WebKit` 等按应用身份派生的目录；本模块把用户习惯与后端业务数据
//! 放在同一目录树下，保证删除应用或重装后仍然保留。
//!
//! 目录结构：
//! - `preferences.json` 前端长期偏好键值（天气/节假日开关、侧边栏宽度等）
//! - `task-desktop.json` 关闭主窗口后是否后台运行
//! - `task-reminder/settings.json` 与 `task-reminder/custom-*.audio` 提醒音色与上传音频
//! - `backend-connection.json` 远程后端地址
//!
//! 旧版本分别写在 Tauri 应用配置目录与 WebView localStorage；启动时执行一次幂等迁移。

use serde::{Deserialize, Serialize};
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::Manager;

/// 前端偏好键至少以该前缀开头，避免该文件被当成任意数据存储。
const KEY_PREFIXES: [&str; 2] = ["butvan.", "butvan-"];
const MAX_KEY_CHARS: usize = 200;
const MAX_VALUE_BYTES: usize = 256 * 1024;
const MAX_ENTRIES: usize = 256;

static PREFERENCES_IO: Mutex<()> = Mutex::new(());

/// 前端长期偏好键值表；读写均在原生侧完成，前端只持有键名。
#[derive(Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct StoredPreferences {
    values: std::collections::BTreeMap<String, String>,
}

/// 桌面偏好根目录，与后端业务数据 `~/.butvan-agent` 同级。
pub fn root(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path()
        .home_dir()
        .map(|dir| dir.join(".butvan-agent").join("desktop"))
        .map_err(|e| format!("无法定位用户目录：{e}"))
}

fn preferences_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    Ok(root(app)?.join("preferences.json"))
}

fn validate_key(key: &str) -> Result<(), String> {
    if key.is_empty() || key.chars().count() > MAX_KEY_CHARS {
        return Err("偏好键名无效".into());
    }
    if !KEY_PREFIXES.iter().any(|prefix| key.starts_with(prefix)) {
        return Err("偏好键名必须以 butvan. 或 butvan- 开头".into());
    }
    Ok(())
}

fn read(app: &tauri::AppHandle) -> Result<StoredPreferences, String> {
    match std::fs::read(preferences_path(app)?) {
        Ok(bytes) => serde_json::from_slice(&bytes).or_else(|e| {
            log::warn!("桌面偏好文件损坏，改用空偏好：{e}");
            Ok(StoredPreferences::default())
        }),
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(StoredPreferences::default()),
        Err(e) => Err(format!("桌面偏好读取失败：{e}")),
    }
}

fn write(app: &tauri::AppHandle, stored: &StoredPreferences) -> Result<(), String> {
    let path = preferences_path(app)?;
    let dir = path.parent().ok_or("桌面偏好目录无效")?;
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let bytes = serde_json::to_vec(stored).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("preferences.json.tmp"), bytes).map_err(|e| e.to_string())?;
    std::fs::rename(dir.join("preferences.json.tmp"), &path).map_err(|e| e.to_string())
}

/// 返回全部长期偏好；文件缺失或损坏时返回空表，由前端回退默认值。
#[tauri::command]
pub fn load_desktop_preferences(app: tauri::AppHandle) -> Result<std::collections::BTreeMap<String, String>, String> {
    let _guard = PREFERENCES_IO.lock().map_err(|e| e.to_string())?;
    Ok(read(&app)?.values)
}

/// 写入单个偏好键，保留其余键；值有大小上限，避免偏好文件无界增长。
#[tauri::command]
pub fn save_desktop_preference(app: tauri::AppHandle, key: String, value: String) -> Result<(), String> {
    let _guard = PREFERENCES_IO.lock().map_err(|e| e.to_string())?;
    validate_key(&key)?;
    if value.len() > MAX_VALUE_BYTES {
        return Err("偏好内容过大，无法保存".into());
    }
    let mut stored = read(&app)?;
    if !stored.values.contains_key(&key) && stored.values.len() >= MAX_ENTRIES {
        return Err("偏好条目过多，无法保存".into());
    }
    stored.values.insert(key, value);
    write(&app, &stored)
}

/// 幂等复制旧位置文件；目标已存在时保留新数据。
fn copy_if_missing(source: &Path, target: &Path) -> Result<(), String> {
    if target.exists() {
        return Ok(());
    }
    match std::fs::copy(source, target) {
        Ok(_) => {
            log::info!("已迁移桌面偏好：{}", source.display());
            Ok(())
        }
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(()),
        Err(e) => Err(format!("迁移 {} 失败：{e}", source.display())),
    }
}

/// 把旧版本写在应用配置目录与 WebView localStorage 之外的偏好搬入新目录。
fn migrate_legacy(app: &tauri::AppHandle) -> Result<(), String> {
    let target = root(app)?;
    let legacy = app.path().app_config_dir().map_err(|e| e.to_string())?;
    migrate_dirs(&legacy, &target)
}

/// 迁移逻辑与 Tauri 路径解耦，便于用临时目录验证幂等性。
fn migrate_dirs(legacy: &Path, target: &Path) -> Result<(), String> {
    if legacy == target {
        return Ok(());
    }
    std::fs::create_dir_all(target).map_err(|e| e.to_string())?;
    for name in ["backend-connection.json", "task-desktop.json"] {
        copy_if_missing(&legacy.join(name), &target.join(name))?;
    }
    let legacy_reminder = legacy.join("task-reminder");
    let target_reminder = target.join("task-reminder");
    if target_reminder.join("settings.json").exists() {
        return Ok(());
    }
    let entries = match std::fs::read_dir(&legacy_reminder) {
        Ok(entries) => entries,
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(format!("读取旧提醒设置失败：{e}")),
    };
    std::fs::create_dir_all(&target_reminder).map_err(|e| e.to_string())?;
    for entry in entries {
        let entry = match entry { Ok(entry) => entry, Err(e) => { log::warn!("旧提醒文件枚举失败：{e}"); continue; } };
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
        if name == "settings.json" || crate::task_reminder_settings::valid_custom_filename(&name) {
            copy_if_missing(&entry.path(), &target_reminder.join(&name))?;
        }
    }
    Ok(())
}

/// 在应用初始化阶段执行一次迁移；失败只记录日志，不阻断启动。
pub fn setup(app: &tauri::AppHandle) {
    if let Err(error) = migrate_legacy(app) {
        log::warn!("桌面偏好迁移未完成：{error}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_butvan_keys_are_accepted() {
        assert!(validate_key("butvan.featurePreferences").is_ok());
        assert!(validate_key("butvan.sidebarWidth").is_ok());
        assert!(validate_key("").is_err());
        assert!(validate_key("/etc/passwd").is_err());
        assert!(validate_key(&"butvan.".repeat(40)).is_err());
    }

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("butvan-prefs-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn migrates_legacy_files_once() {
        let base = temp_dir("migrate");
        let legacy = base.join("legacy");
        let target = base.join("desktop");
        std::fs::create_dir_all(legacy.join("task-reminder")).unwrap();
        std::fs::write(legacy.join("task-desktop.json"), "true").unwrap();
        std::fs::write(legacy.join("backend-connection.json"), "{}").unwrap();
        std::fs::write(legacy.join("task-reminder/settings.json"), "{}").unwrap();
        std::fs::write(legacy.join("task-reminder/custom-1.audio"), b"audio").unwrap();
        std::fs::write(legacy.join("task-reminder/ignore.txt"), b"x").unwrap();

        migrate_dirs(&legacy, &target).unwrap();
        assert_eq!(std::fs::read_to_string(target.join("task-desktop.json")).unwrap(), "true");
        assert_eq!(std::fs::read(target.join("task-reminder/custom-1.audio")).unwrap(), b"audio");
        assert!(target.join("task-reminder/settings.json").exists());
        assert!(!target.join("task-reminder/ignore.txt").exists());

        // 已存在的目标文件必须保留新数据，不被旧文件覆盖。
        std::fs::write(target.join("task-desktop.json"), "false").unwrap();
        migrate_dirs(&legacy, &target).unwrap();
        assert_eq!(std::fs::read_to_string(target.join("task-desktop.json")).unwrap(), "false");
        std::fs::remove_dir_all(&base).unwrap();
    }

    #[test]
    fn missing_values_default_to_empty() {
        let stored: StoredPreferences = serde_json::from_str("{}").unwrap();
        assert!(stored.values.is_empty());
    }
}
