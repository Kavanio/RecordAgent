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
    revision: u64,
    active_study: Option<ActiveStudy>,
}

/// 保存后端最近一次推送的权威状态，避免多个 WebView 的旧快照覆盖新状态。
#[derive(Default)]
pub struct StudyTrayState(Mutex<Snapshot>);

/// 同步后端学习状态。只有 macOS 展示菜单栏计时，其他平台安全忽略。
#[tauri::command(rename_all = "camelCase")]
pub fn sync_study_tray(
    state: tauri::State<'_, StudyTrayState>,
    revision: u64,
    force_snapshot: bool,
    active_study: Option<ActiveStudy>,
) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        if active_study
            .as_ref()
            .is_some_and(|study| study.id.is_empty() || study.id.len() > 128)
        {
            return Err("学习状态无效，无法更新菜单栏计时".into());
        }
        let mut snapshot = state.0.lock().map_err(|_| "菜单栏学习状态暂不可用")?;
        // 服务端重启后 revision 会从零开始；新连接的快照仍是权威状态。
        if force_snapshot || revision >= snapshot.revision {
            snapshot.revision = revision;
            snapshot.active_study = active_study;
        }
    }
    #[cfg(not(target_os = "macos"))]
    let _ = (state, revision, force_snapshot, active_study);
    Ok(())
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
