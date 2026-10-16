//! macOS 菜单栏中的学习计时展示。
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::Deserialize;
use tauri::Manager;

const TRAY_ID: &str = "butvan-agent-tray";

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActiveStudy {
    id: String,
    started_at_epoch_ms: i64,
}

#[derive(Default)]
struct Snapshot {
    stream_id: Option<String>,
    revision: u64,
    active_study: Option<ActiveStudy>,
}

/// 保存后端最近一次推送的权威状态，避免多个 WebView 的旧快照覆盖新状态。
#[derive(Default)]
pub struct StudyTrayState(Mutex<Snapshot>);

/// 同步后端学习状态。只有 macOS 展示菜单栏计时，其他平台安全忽略。
#[tauri::command(rename_all = "camelCase")]
pub fn sync_study_tray(
    app: tauri::AppHandle,
    state: tauri::State<'_, StudyTrayState>,
    stream_id: String,
    revision: u64,
    active_study: Option<ActiveStudy>,
) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        if stream_id.is_empty() || stream_id.len() > 128 {
            return Err("学习状态版本无效，无法更新菜单栏".into());
        }
        if active_study
            .as_ref()
            .is_some_and(|study| study.id.is_empty() || study.id.len() > 128)
        {
            return Err("学习状态无效，无法更新菜单栏计时".into());
        }
        let has_active_study = {
            let mut snapshot = state.0.lock().map_err(|_| "菜单栏学习状态暂不可用")?;
            // streamId 变化代表后端重启；同一进程内始终拒绝较旧的窗口快照。
            if snapshot.stream_id.as_deref() != Some(stream_id.as_str())
                || revision >= snapshot.revision
            {
                snapshot.stream_id = Some(stream_id);
                snapshot.revision = revision;
                snapshot.active_study = active_study;
            }
            snapshot.active_study.is_some()
        };
        update_study_menu(&app, has_active_study)?;
    }
    #[cfg(not(target_os = "macos"))]
    let _ = (app, state, stream_id, revision, active_study);
    Ok(())
}

/// 仅在学习进行中时把结束操作放入菜单，其他时候彻底移除该菜单项。
#[cfg(target_os = "macos")]
fn update_study_menu(app: &tauri::AppHandle, has_active_study: bool) -> Result<(), String> {
    use tauri::menu::{Menu, MenuItem};

    let show = MenuItem::with_id(app, "task-show", "打开 ButvanAgent", true, None::<&str>)
        .map_err(|error| error.to_string())?;
    let quit = MenuItem::with_id(app, "task-quit", "退出（停止所有任务）", true, None::<&str>)
        .map_err(|error| error.to_string())?;
    let finish = if has_active_study {
        Some(
            MenuItem::with_id(app, "study-finish", "结束学习", true, None::<&str>)
                .map_err(|error| error.to_string())?,
        )
    } else {
        None
    };

    let menu = Menu::new(app).map_err(|error| error.to_string())?;
    menu.append(&show).map_err(|error| error.to_string())?;
    if let Some(finish) = finish.as_ref() {
        menu.append(finish).map_err(|error| error.to_string())?;
    }
    menu.append(&quit).map_err(|error| error.to_string())?;

    let tray = app
        .tray_by_id(TRAY_ID)
        .ok_or_else(|| "状态栏图标尚未就绪".to_string())?;
    tray.set_menu(Some(menu)).map_err(|error| error.to_string())
}

fn elapsed_title(started_at_epoch_ms: i64, now_epoch_ms: i64) -> String {
    let elapsed_seconds = now_epoch_ms.saturating_sub(started_at_epoch_ms).max(0) as u64 / 1_000;
    let hours = elapsed_seconds / 3_600;
    let minutes = (elapsed_seconds % 3_600) / 60;
    let seconds = elapsed_seconds % 60;
    if hours > 0 {
        format!("{hours}:{minutes:02}:{seconds:02}")
    } else {
        format!("{minutes:02}:{seconds:02}")
    }
}

fn now_epoch_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_millis()
        .min(i64::MAX as u128) as i64
}

/// 每秒根据开始时间重算显示值；系统休眠或进程恢复后会立即追上真实时长。
pub fn setup(app: &tauri::AppHandle) {
    app.manage(StudyTrayState::default());

    #[cfg(target_os = "macos")]
    {
        let app = app.clone();
        std::thread::spawn(move || {
            let mut last_title = None::<String>;
            loop {
                let active = app
                    .state::<StudyTrayState>()
                    .0
                    .lock()
                    .ok()
                    .and_then(|snapshot| snapshot.active_study.clone());
                let title = active
                    .map(|study| elapsed_title(study.started_at_epoch_ms, now_epoch_ms()))
                    .unwrap_or_default();

                if last_title.as_deref() != Some(title.as_str()) {
                    if let Some(tray) = app.tray_by_id(TRAY_ID) {
                        if let Err(error) = tray.set_title(Some(title.as_str())) {
                            log::warn!("更新菜单栏学习计时失败：{}", error);
                        }
                    }
                    last_title = Some(title);
                }
                std::thread::sleep(Duration::from_secs(1));
            }
        });
    }
}
