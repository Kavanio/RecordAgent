//! 桌面提醒的本机偏好、受限声音目录与用户主动导入的音频。
use serde::{Deserialize, Serialize};
use std::{path::PathBuf, sync::Mutex};
use tauri::Manager;

static SETTINGS_IO: Mutex<()> = Mutex::new(());
const MAX_AUDIO_BYTES: usize = 10 * 1024 * 1024;

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Preferences {
    pub rest_lock: bool,
    pub sound_id: String,
    pub repeat_seconds: u32,
}
impl Default for Preferences {
    fn default() -> Self {
        Self { rest_lock: false, sound_id: "builtin:bell".into(), repeat_seconds: 4 }
    }
}
#[derive(Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct StoredSettings {
    preferences: Preferences,
    custom_name: Option<String>,
    custom_file: Option<String>,
}
#[derive(Serialize)]
pub struct Sound { id: String, name: String, source: &'static str }
#[derive(Serialize)]
pub struct Configuration { preferences: Preferences, sounds: Vec<Sound> }

fn directory(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path().app_config_dir().map(|dir| dir.join("task-reminder")).map_err(|e| e.to_string())
}
fn read(app: &tauri::AppHandle) -> Result<StoredSettings, String> {
    match std::fs::read(directory(app)?.join("settings.json")) {
        Ok(bytes) => serde_json::from_slice(&bytes).or_else(|e| {
            log::warn!("提醒设置损坏，使用不锁定桌面的默认设置：{e}");
            Ok(StoredSettings::default())
        }),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(StoredSettings::default()),
        Err(e) => Err(format!("提醒设置读取失败：{e}")),
    }
}
fn write(app: &tauri::AppHandle, settings: &StoredSettings) -> Result<(), String> {
    let dir = directory(app)?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let bytes = serde_json::to_vec(settings).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("settings.json.tmp"), bytes).map_err(|e| e.to_string())?;
    std::fs::rename(dir.join("settings.json.tmp"), dir.join("settings.json")).map_err(|e| e.to_string())
}

/// 系统目录由应用指定，IPC 只接收目录内的稳定文件名，不接受任意路径。
fn system_directory() -> Option<PathBuf> {
    #[cfg(target_os = "macos")]
    { Some(PathBuf::from("/System/Library/Sounds")) }
    #[cfg(target_os = "windows")]
    { std::env::var_os("WINDIR").map(|dir| PathBuf::from(dir).join("Media")) }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    { None }
}
fn system_sounds() -> Vec<Sound> {
    let Some(dir) = system_directory() else { return vec![]; };
    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(e) => { log::warn!("系统提示音目录不可用：{e}"); return vec![]; }
    };
    let mut sounds = vec![];
    for entry in entries {
        let entry = match entry { Ok(entry) => entry, Err(e) => { log::warn!("系统声音枚举失败：{e}"); continue; } };
        let path = entry.path();
        let ext = path.extension().and_then(|v| v.to_str()).unwrap_or("").to_ascii_lowercase();
        if !matches!(ext.as_str(), "aiff" | "aif" | "wav" | "mp3" | "m4a") || !path.is_file() { continue; }
        if let (Some(file), Some(name)) = (path.file_name().and_then(|v| v.to_str()), path.file_stem().and_then(|v| v.to_str())) {
            sounds.push(Sound { id: format!("system:{file}"), name: name.into(), source: "system" });
        }
    }
    sounds.sort_by(|a, b| a.name.cmp(&b.name));
    sounds
}
pub fn system_path(id: &str) -> Result<PathBuf, String> {
    if !system_sounds().iter().any(|sound| sound.id == id) { return Err("系统提示音不存在".into()); }
    system_directory().map(|dir| dir.join(id.trim_start_matches("system:"))).ok_or("当前系统没有可用的提示音目录".into())
}
fn configuration(stored: StoredSettings) -> Configuration {
    let mut sounds: Vec<Sound> = [("bell", "清亮铃声"), ("chime", "柔和风铃"), ("pulse", "双音提醒"), ("rise", "上升音阶"), ("soft", "轻柔单音")]
        .into_iter().map(|(id, name)| Sound { id: format!("builtin:{id}"), name: name.into(), source: "builtin" }).collect();
    sounds.extend(system_sounds());
    if let Some(name) = stored.custom_name { sounds.push(Sound { id: "custom".into(), name, source: "custom" }); }
    Configuration { preferences: stored.preferences, sounds }
}

#[tauri::command]
pub fn task_reminder_configuration(app: tauri::AppHandle) -> Result<Configuration, String> {
    let _guard = SETTINGS_IO.lock().map_err(|e| e.to_string())?;
    Ok(configuration(read(&app)?))
}
#[tauri::command]
pub fn save_task_reminder_preferences(app: tauri::AppHandle, window: tauri::WebviewWindow, preferences: Preferences) -> Result<(), String> {
    let _guard = SETTINGS_IO.lock().map_err(|e| e.to_string())?;
    if window.label() != "main" { return Err("请在主窗口保存提醒设置".into()); }
    if !(1..=3600).contains(&preferences.repeat_seconds) { return Err("播放间隔须为 1 至 3600 秒".into()); }
    let mut stored = read(&app)?;
    if !configuration(read(&app)?).sounds.iter().any(|sound| sound.id == preferences.sound_id) {
        return Err("请选择可用的提示音".into());
    }
    stored.preferences = preferences;
    write(&app, &stored)
}

/// 文件由用户通过文件选择器主动上传；大小、扩展名和文件头均在原生侧校验。
#[tauri::command]
pub fn import_task_reminder_audio(app: tauri::AppHandle, window: tauri::WebviewWindow, name: String, bytes: Vec<u8>) -> Result<Configuration, String> {
    let _guard = SETTINGS_IO.lock().map_err(|e| e.to_string())?;
    if window.label() != "main" { return Err("请在主窗口上传音频".into()); }
    validate_audio(&name, &bytes)?;
    #[cfg(target_os = "macos")]
    {
        use objc2::AnyThread;
        use objc2_app_kit::NSSound;
        use objc2_foundation::NSData;
        if NSSound::initWithData(NSSound::alloc(), &NSData::with_bytes(&bytes)).is_none() {
            return Err("系统播放器无法读取此音频，请改用 WAV 或 MP3 文件".into());
        }
    }
    let mut stored = read(&app)?;
    let dir = directory(&app)?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let revision = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|e| e.to_string())?.as_nanos();
    let filename = format!("custom-{revision}.audio");
    std::fs::write(dir.join(&filename), bytes).map_err(|e| e.to_string())?;
    let previous = stored.custom_file.replace(filename.clone());
    stored.custom_name = Some(name);
    stored.preferences.sound_id = "custom".into();
    if let Err(error) = write(&app, &stored) {
        if let Err(e) = std::fs::remove_file(dir.join(&filename)) { log::warn!("导入失败后的音频清理失败：{e}"); }
        return Err(error);
    }
    // 配置提交成功后才删除旧文件；写入失败不会破坏正在使用的音频。
    if let Some(previous) = previous.filter(|name| valid_custom_filename(name)) {
        if let Err(e) = std::fs::remove_file(dir.join(previous)) { log::warn!("旧提示音清理失败：{e}"); }
    }
    Ok(configuration(stored))
}
fn valid_custom_filename(name: &str) -> bool {
    name.strip_prefix("custom-").and_then(|name| name.strip_suffix(".audio"))
        .is_some_and(|revision| !revision.is_empty() && revision.bytes().all(|byte| byte.is_ascii_digit()))
}
fn validate_audio(name: &str, bytes: &[u8]) -> Result<(), String> {
    if bytes.is_empty() || bytes.len() > MAX_AUDIO_BYTES || name.chars().count() > 200 {
        return Err("音频须非空且不超过 10 MB，文件名不超过 200 字".into());
    }
    let ext = name.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    let valid = match ext.as_str() {
        "wav" => bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WAVE"),
        "mp3" => bytes.starts_with(b"ID3") || (bytes.len() > 1 && bytes[0] == 0xff && bytes[1] & 0xe0 == 0xe0),
        "m4a" => bytes.get(4..8) == Some(b"ftyp"),
        "ogg" => bytes.starts_with(b"OggS"),
        _ => false,
    };
    if valid { Ok(()) } else { Err("请选择有效的 WAV、MP3、M4A 或 OGG 音频文件".into()) }
}
#[tauri::command]
pub fn read_task_reminder_audio(app: tauri::AppHandle, id: String) -> Result<Vec<u8>, String> {
    let _guard = SETTINGS_IO.lock().map_err(|e| e.to_string())?;
    let path = if id == "custom" {
        let file = read(&app)?.custom_file.filter(|name| valid_custom_filename(name)).ok_or("上传的音频不存在，请重新上传")?;
        directory(&app)?.join(file)
    } else { system_path(&id)? };
    if std::fs::metadata(&path).map_err(|e| e.to_string())?.len() > MAX_AUDIO_BYTES as u64 { return Err("音频不能超过 10 MB".into()); }
    std::fs::read(path).map_err(|e| format!("音频读取失败：{e}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn defaults_keep_desktop_unlocked() {
        let settings: StoredSettings = serde_json::from_str("{}").unwrap();
        assert!(!settings.preferences.rest_lock);
        assert_eq!(settings.preferences.repeat_seconds, 4);
    }
    #[test]
    fn rejects_disguised_and_oversized_uploads() {
        assert!(validate_audio("test.mp3", b"<script>bad</script>").is_err());
        assert!(validate_audio("test.wav", b"RIFF0000WAVEdata").is_ok());
        assert!(validate_audio("test.exe", b"RIFF0000WAVEdata").is_err());
        assert!(validate_audio("test.mp3", &vec![0; MAX_AUDIO_BYTES + 1]).is_err());
        assert!(system_path("system:../../etc/passwd").is_err());
    }
}
