//! 休息倒计时的多屏遮罩。仅拦截普通窗口点击，不接管系统快捷键。
use std::{sync::Mutex, time::{Duration, Instant}};
use tauri::Manager;

#[derive(Clone, Copy, PartialEq, Eq)]
enum LeaseKind { Rest, Preview }
#[derive(Clone, Copy)]
struct Lease { until: Instant, kind: LeaseKind }
static LEASE: Mutex<Option<Lease>> = Mutex::new(None);

/// 倒计时结束、偏好关闭或原生租约到期后销毁全部遮罩。
pub fn clear(app: &tauri::AppHandle) -> Result<(), String> {
    clear_matching(app, None)
}

/// 释放正式休息遮罩，不影响设置页的锁屏效果预览。
pub fn clear_rest(app: &tauri::AppHandle) -> Result<(), String> {
    clear_matching(app, Some(LeaseKind::Rest))
}

/// 释放设置页预览，不影响正式休息遮罩。
fn clear_preview(app: &tauri::AppHandle) -> Result<(), String> {
    clear_matching(app, Some(LeaseKind::Preview))
}

fn clear_matching(app: &tauri::AppHandle, expected: Option<LeaseKind>) -> Result<(), String> {
    let current = *LEASE.lock().map_err(|e| e.to_string())?;
    if expected.is_some() && current.is_some_and(|lease| Some(lease.kind) != expected) { return Ok(()); }
    *LEASE.lock().map_err(|e| e.to_string())? = None;
    let mut error = None;
    for (label, window) in app.webview_windows() {
        if label.starts_with("task-rest-shield-") {
            if let Err(e) = window.destroy() { error = Some(e.to_string()); }
        }
    }
    if let Some(reminder) = app.get_webview_window("task-reminder") {
        if let Err(e) = set_macos_level(&reminder, 3) { error = Some(e); }
    }
    if error.is_some() {
        *LEASE.lock().map_err(|e| e.to_string())? = Some(Lease {
            until: Instant::now(), kind: current.map(|lease| lease.kind).or(expected).unwrap_or(LeaseKind::Rest),
        });
    }
    error.map_or(Ok(()), Err)
}

/// 只允许提醒窗口按一秒心跳续约；原生看门狗避免脚本失效后永久遮挡。
/// 暂时断连时由既有租约自然到期，避免连接状态抖动造成遮罩反复销毁和重建。
#[tauri::command]
pub fn sync_task_rest_lock(app: tauri::AppHandle, window: tauri::WebviewWindow, remaining_seconds: u32) -> Result<(), String> {
    if window.label() != "task-reminder" { return Err("仅提醒窗口可以控制休息遮罩".into()); }
    if remaining_seconds == 0 { return clear_rest(&app); }
    let lease_secs = u64::from(remaining_seconds.min(15).max(6));
    *LEASE.lock().map_err(|e| e.to_string())? = Some(Lease { until: Instant::now() + Duration::from_secs(lease_secs), kind: LeaseKind::Rest });
    let has_existing_shields = app.webview_windows().keys().any(|label| label.starts_with("task-rest-shield-"));
    if let Err(error) = show(&app, &window) {
        // 已显示时保留当前遮罩并由下一次心跳重试；首次创建失败才清理可能的半成品。
        if !has_existing_shields {
            if let Err(cleanup_error) = clear(&app) { log::error!("首次创建失败后的休息遮罩清理失败：{cleanup_error}"); }
        }
        return Err(error);
    }
    Ok(())
}

/// 预览锁屏效果（主窗口等可用），0 释放，正数直接按指定剩余时间加缓冲设置租约（无需每秒心跳）。
#[tauri::command]
pub fn preview_task_rest_lock(app: tauri::AppHandle, remaining_seconds: u32) -> Result<(), String> {
    if remaining_seconds == 0 { return clear_preview(&app); }
    let lease_secs = u64::from(remaining_seconds.clamp(10, 300) + 5);
    *LEASE.lock().map_err(|e| e.to_string())? = Some(Lease { until: Instant::now() + Duration::from_secs(lease_secs), kind: LeaseKind::Preview });
    let has_existing_shields = app.webview_windows().keys().any(|label| label.starts_with("task-rest-shield-"));
    if let Err(error) = show_shields(&app) {
        if !has_existing_shields {
            if let Err(cleanup_error) = clear(&app) { log::error!("首次创建失败后的休息遮罩清理失败：{cleanup_error}"); }
        }
        return Err(error);
    }
    Ok(())
}

fn show_shields(app: &tauri::AppHandle) -> Result<(), String> {
    let monitors = app.available_monitors().map_err(|e| e.to_string())?;
    for (index, monitor) in monitors.iter().enumerate() {
        let label = format!("task-rest-shield-{index}");
        let (shield, created) = if let Some(shield) = app.get_webview_window(&label) {
            (shield, false)
        } else {
            let shield = tauri::WebviewWindowBuilder::new(app, &label, tauri::WebviewUrl::App("task-rest-shield.html".into()))
                .title("正在休息").decorations(false).resizable(false).maximizable(false).minimizable(false)
                .always_on_top(true).visible_on_all_workspaces(true).skip_taskbar(true).focused(true).focusable(true).visible(false)
                .build().map_err(|e| e.to_string())?;
            (shield, true)
        };

        // 显示器热插拔、排列变化或分辨率变化时，按当前列表序号重新校准窗口。
        // 只在几何尺寸确实变化时调用原生 API，避免每秒心跳触发 WebView 重绘闪屏。
        let desired_position = *monitor.position();
        if shield.outer_position().map_err(|e| e.to_string())? != desired_position {
            #[cfg(target_os = "macos")]
            shield.set_position(desired_position.to_logical::<f64>(monitor.scale_factor())).map_err(|e| e.to_string())?;
            #[cfg(not(target_os = "macos"))]
            shield.set_position(desired_position).map_err(|e| e.to_string())?;
        }

        #[cfg(target_os = "macos")]
        let (expected_size, desired_size) = {
            // Tao Monitor.size 是按 backing scale 换算后的物理像素；NSWindow 尺寸使用点，
            // 因此比较时保留物理尺寸，设置时只换算一次到目标屏幕的逻辑尺寸。
            let scale = monitor.scale_factor();
            let size = *monitor.size();
            (size, size.to_logical::<f64>(scale))
        };
        #[cfg(not(target_os = "macos"))]
        let (expected_size, desired_size) = (*monitor.size(), *monitor.size());

        if shield.outer_size().map_err(|e| e.to_string())? != expected_size {
            shield.set_size(desired_size).map_err(|e| e.to_string())?;
        }

        if created || !shield.is_visible().map_err(|e| e.to_string())? {
            // macOS 状态栏层覆盖菜单栏与 Dock；全屏展示 3D 星球与倒计时。
            set_macos_level(&shield, 25)?;
            shield.show().map_err(|e| e.to_string())?;
        }
    }
    // 拔除屏幕后及时移除不再对应显示器的遮罩。
    for (label, window) in app.webview_windows() {
        if label.strip_prefix("task-rest-shield-").and_then(|v| v.parse::<usize>().ok()).is_some_and(|index| index >= monitors.len()) {
            window.destroy().map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

fn show(app: &tauri::AppHandle, reminder: &tauri::WebviewWindow) -> Result<(), String> {
    show_shields(app)?;
    // 隐藏小提醒窗口，使全屏 3D 休息画面完整展示
    let _ = reminder.hide();
    Ok(())
}
fn set_macos_level(window: &tauri::WebviewWindow, level: isize) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let ptr = window.ns_window().map_err(|e| e.to_string())?;
        // 指针来自存活的 Tauri 窗口，仅在主线程的命令或看门狗回调中使用。
        let native = unsafe { &*ptr.cast::<objc2_app_kit::NSWindow>() };
        native.setLevel(level);
    }
    #[cfg(not(target_os = "macos"))]
    let _ = (window, level);
    Ok(())
}
pub fn setup(app: &tauri::AppHandle) {
    let handle = app.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(1));
        let app = handle.clone();
        if let Err(e) = handle.run_on_main_thread(move || {
            let lease = LEASE.lock().map(|lease| *lease).unwrap_or(None);
            match lease {
                Some(lease) if Instant::now() < lease.until => {
                    // 预览不持续发送心跳，也要能在预览期间适配显示器热插拔和布局变化。
                    if let Err(e) = show_shields(&app) {
                        log::error!("休息遮罩显示器布局同步失败：{e}");
                        // 暂时的显示器枚举/窗口布局错误不销毁现有遮罩，下一秒继续同步。
                    }
                }
                Some(_) => {
                    if let Err(e) = clear(&app) { log::error!("休息遮罩超时清理失败：{e}"); }
                }
                None => {}
            }
        }) { log::warn!("休息遮罩看门狗停止：{e}"); break; }
    });
}
