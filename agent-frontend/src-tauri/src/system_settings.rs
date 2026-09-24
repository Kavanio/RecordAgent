//! 桌面系统设置入口。
//!
//! 仅暴露固定的系统设置页面，不接受前端传入 URL 或命令参数。

use std::process::Command;

/// 打开当前系统的定位隐私设置页。
#[tauri::command]
pub fn open_location_settings() -> Result<(), String> {
    open_platform_location_settings()
}

/// 打开当前系统的通知设置页。
#[tauri::command]
pub fn open_notification_settings() -> Result<(), String> {
    open_platform_notification_settings()
}

#[cfg(target_os = "macos")]
fn open_platform_location_settings() -> Result<(), String> {
    spawn_settings_command(
        "open",
        &["x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices"],
    )
}

#[cfg(target_os = "macos")]
fn open_platform_notification_settings() -> Result<(), String> {
    spawn_settings_command(
        "open",
        &["x-apple.systempreferences:com.apple.Notifications-Settings.extension"],
    )
}

#[cfg(target_os = "windows")]
fn open_platform_notification_settings() -> Result<(), String> {
    spawn_settings_command("explorer.exe", &["ms-settings:notifications"])
}

#[cfg(target_os = "linux")]
fn open_platform_notification_settings() -> Result<(), String> {
    Err("当前桌面环境不支持直接打开通知设置，请在系统设置的通知页面中手工开启。".to_string())
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
fn open_platform_notification_settings() -> Result<(), String> {
    Err("当前系统不支持直接打开通知设置，请在系统设置中手工开启。".to_string())
}

#[cfg(target_os = "windows")]
fn open_platform_location_settings() -> Result<(), String> {
    spawn_settings_command("explorer.exe", &["ms-settings:privacy-location"])
}

#[cfg(target_os = "linux")]
fn open_platform_location_settings() -> Result<(), String> {
    spawn_settings_command("gnome-control-center", &["privacy", "location"])
        .or_else(|_| spawn_settings_command("systemsettings", &["kcm_location"]))
        .map_err(|_| "当前桌面环境不支持直接打开定位设置，请在系统隐私设置中手工开启。".to_string())
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
fn open_platform_location_settings() -> Result<(), String> {
    Err("当前系统不支持直接打开定位设置。".to_string())
}

fn spawn_settings_command(program: &str, args: &[&str]) -> Result<(), String> {
    Command::new(program)
        .args(args)
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("无法打开系统设置：{error}"))
}
