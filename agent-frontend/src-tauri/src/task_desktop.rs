//! 自动任务原生适配：采集空闲时长、固定提醒窗口和用户可控后台驻留。
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tauri::{Emitter, Manager};
use serde::Serialize;

pub struct TaskDesktopState { pub background: AtomicBool }
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Activity { idle_seconds: u64, locked: bool, supported: bool }

/// 只读取系统累计空闲时间与会话状态，不安装按键监听，不获取屏幕内容。
#[cfg(target_os = "macos")]
fn activity() -> Activity {
    use std::ffi::{c_void, CString};
    #[link(name = "ApplicationServices", kind = "framework")]
    extern "C" {
        fn CGEventSourceSecondsSinceLastEventType(state: i32, event: u32) -> f64;
        fn CGSessionCopyCurrentDictionary() -> *const c_void;
    }
    #[link(name = "CoreFoundation", kind = "framework")]
    extern "C" {
        fn CFStringCreateWithCString(allocator: *const c_void, text: *const i8, encoding: u32) -> *const c_void;
        fn CFDictionaryGetValue(dictionary: *const c_void, key: *const c_void) -> *const c_void;
        fn CFBooleanGetValue(value: *const c_void) -> bool;
        fn CFRelease(value: *const c_void);
    }
    unsafe {
        let idle = CGEventSourceSecondsSinceLastEventType(1, u32::MAX);
        let session = CGSessionCopyCurrentDictionary();
        if session.is_null() || !idle.is_finite() || idle < 0.0 {
            if !session.is_null() { CFRelease(session); }
            return Activity { idle_seconds: 0, locked: true, supported: false };
        }
        // 会话字典的锁屏键存在时暂停；控制台会话缺失时也安全暂停。
        let bool_value = |name: &str| -> Option<bool> {
            let name = CString::new(name).expect("fixed dictionary key");
            let key = CFStringCreateWithCString(std::ptr::null(), name.as_ptr(), 0x08000100);
            let value = CFDictionaryGetValue(session, key);
            let result = if value.is_null() { None } else { Some(CFBooleanGetValue(value)) };
            CFRelease(key); result
        };
        let locked = bool_value("CGSSessionScreenIsLocked").unwrap_or(false)
            || !bool_value("kCGSSessionOnConsoleKey").unwrap_or(false);
        CFRelease(session);
        Activity { idle_seconds: idle as u64, locked, supported: true }
    }
}
#[cfg(not(target_os = "macos"))]
fn activity() -> Activity { Activity { idle_seconds: 0, locked: false, supported: false } }

/// 获取检测能力与一次快照，浏览器不能伪装支持系统检测。
#[tauri::command]
pub fn task_activity_status() -> Activity { activity() }

/// 固定路由提醒窗口，不接受任意 URL 或脚本。
#[tauri::command]
pub fn sync_task_reminder(app: tauri::AppHandle, visible: bool) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("task-reminder") {
        return if visible { window.show() } else { window.hide() }.map_err(|e| e.to_string());
    }
    if visible {
        tauri::WebviewWindowBuilder::new(&app, "task-reminder", tauri::WebviewUrl::App("index.html?view=task-reminder".into()))
            .title("任务提醒").inner_size(440.0, 440.0).min_inner_size(360.0, 300.0)
            .always_on_top(true).resizable(true).build().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// 当前通知能力；开发可执行文件没有稳定包身份，改用可见的提醒窗口。
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskNotificationStatus {
    mode: &'static str,
    authorized: bool,
    alert_enabled: bool,
    sound_enabled: bool,
}

#[tauri::command]
pub fn task_notification_status() -> TaskNotificationStatus {
    #[cfg(target_os = "macos")]
    {
        use mac_usernotifications::{AuthorizationStatus, NotificationSettingStatus};
        if tauri::is_dev() || mac_usernotifications::check_bundle().is_err() {
            return TaskNotificationStatus { mode: "DEV_FALLBACK", authorized: true, alert_enabled: true, sound_enabled: true };
        }
        if let Ok(settings) = mac_usernotifications::blocking::get_notification_settings() {
            return TaskNotificationStatus {
                mode: "NATIVE",
                authorized: settings.authorization_status == AuthorizationStatus::Authorized,
                alert_enabled: settings.alert_enabled == NotificationSettingStatus::Enabled,
                sound_enabled: settings.sound_enabled == NotificationSettingStatus::Enabled,
            };
        }
        return TaskNotificationStatus { mode: "NATIVE", authorized: false, alert_enabled: false, sound_enabled: false };
    }
    #[cfg(not(target_os = "macos"))]
    TaskNotificationStatus { mode: "OTHER", authorized: true, alert_enabled: true, sound_enabled: true }
}

/// 用户主动在设置页请求横幅与声音权限。
#[tauri::command]
pub fn request_task_notification_permission() -> Result<TaskNotificationStatus, String> {
    #[cfg(target_os = "macos")]
    if !tauri::is_dev() && mac_usernotifications::check_bundle().is_ok() {
        mac_usernotifications::blocking::request_auth()
            .map_err(|e| { log::warn!("通知授权失败：{}", e); "系统通知授权失败".to_string() })?;
    }
    Ok(task_notification_status())
}

/// 安装版等待系统接纳通知；开发版以提醒窗和系统提示音及时提示。
#[tauri::command]
pub fn send_task_notification(app: tauri::AppHandle, title: String, body: String, sound: bool) -> Result<(), String> {
    if title.chars().count() > 100 || body.chars().count() > 400 {
        return Err("通知内容过长".into());
    }
    #[cfg(target_os = "macos")]
    {
        if task_notification_status().mode == "DEV_FALLBACK" {
            if sound {
                app.run_on_main_thread(|| {
                    use objc2_app_kit::NSSound;
                    use objc2_foundation::NSString;
                    let played = NSSound::soundNamed(&NSString::from_str("Ping"))
                        .is_some_and(|tone| tone.play());
                    if !played {
                        unsafe {
                            #[link(name = "AppKit", kind = "framework")]
                            extern "C" { fn NSBeep(); }
                            NSBeep();
                        }
                    }
                }).map_err(|e| format!("提醒声音无法播放：{}", e))?;
            }
            return Ok(());
        }
        let settings = task_notification_status();
        if !settings.authorized || !settings.alert_enabled {
            return Err("请在系统设置 → 通知 → ButvanAgent 开启通知横幅".into());
        }
        let mut notification = mac_usernotifications::Notification::new().title(title).message(body);
        if sound { notification = notification.default_sound(); }
        return notification.send_blocking()
            .map(|_| ())
            .map_err(|e| { log::warn!("任务原生通知提交失败：{}", e); "系统通知提交失败，请检查系统通知设置".into() });
    }
    #[cfg(not(target_os = "macos"))]
    {
        use tauri_plugin_notification::NotificationExt;
        let mut notification = app.notification().builder().title(title).body(body);
        if sound { notification = notification.sound("default"); }
        notification.show().map_err(|e| { log::warn!("任务系统通知提交失败：{}", e); "系统通知提交失败".into() })
    }
}

/// 返回持久化后台偏好；不默认改变关闭窗口行为。
#[tauri::command]
pub fn task_background_enabled(state: tauri::State<'_, TaskDesktopState>) -> bool {
    state.background.load(Ordering::Relaxed)
}
/// 用户主动设置后台驻留，写入 Tauri 应用配置目录，不访问业务 SQLite。
#[tauri::command]
pub fn set_task_background(app: tauri::AppHandle, enabled: bool) -> Result<(), String> {
    let dir = app.path().app_config_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("task-desktop.json"), if enabled { "true" } else { "false" }).map_err(|e| e.to_string())?;
    app.state::<TaskDesktopState>().background.store(enabled, Ordering::Relaxed);
    Ok(())
}
/// 提醒窗口可打开主窗口管理任务，不能通过关闭窗口默认为确认。
#[tauri::command]
pub fn open_task_main(app: tauri::AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("main") {
        window.show().map_err(|e| e.to_string())?;
        window.set_focus().map_err(|e| e.to_string())?;
    }
    Ok(())
}

pub fn setup(app: &tauri::AppHandle) -> Result<(), Box<dyn std::error::Error>> {
    let file = app.path().app_config_dir()?.join("task-desktop.json");
    let background = match std::fs::read_to_string(file) {
        Ok(value) => value.trim() == "true",
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
        Err(e) => { log::warn!("后台偏好读取失败：{}", e); false }
    };
    app.manage(TaskDesktopState { background: AtomicBool::new(background) });
    let show = tauri::menu::MenuItem::with_id(app, "task-show", "打开 ButvanAgent", true, None::<&str>)?;
    let quit = tauri::menu::MenuItem::with_id(app, "task-quit", "退出（停止所有任务）", true, None::<&str>)?;
    let menu = tauri::menu::Menu::with_items(app, &[&show, &quit])?;
    let mut tray = tauri::tray::TrayIconBuilder::new().tooltip("ButvanAgent · 任务后台服务").menu(&menu)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "task-show" => { if let Err(e) = open_task_main(app.clone()) { log::warn!("打开主窗口失败：{}", e); } }
            "task-quit" => app.exit(0),
            _ => {}
        });
    // 菜单栏使用独立的单色图标；macOS 根据明暗模式为模板图标着色。
    let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/menu-bar-icon.png"))?;
    tray = tray.icon(icon).icon_as_template(true);
    tray.build(app)?;
    let handle = app.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(5));
        if let Err(e) = handle.emit_to("main", "task-activity", activity()) {
            log::debug!("任务使用状态发送失败：{}", e);
        }
    });
    Ok(())
}
