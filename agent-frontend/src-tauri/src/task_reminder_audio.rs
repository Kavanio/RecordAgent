//! macOS 提醒音频使用原生播放器，避免无人交互时 WebView 自动播放限制。
#[cfg(target_os = "macos")]
thread_local! {
    static TONES: std::cell::RefCell<std::collections::HashMap<String, objc2::rc::Retained<objc2_app_kit::NSSound>>> =
        std::cell::RefCell::new(std::collections::HashMap::new());
}

/// 试听与正式提醒使用独立通道；状态查询反映音频真实播放完成。
#[tauri::command]
pub async fn task_reminder_audio(app: tauri::AppHandle, window: tauri::WebviewWindow, id: String, action: String, channel: String, bytes: Option<Vec<u8>>) -> Result<bool, String> {
    let allowed = match channel.as_str() {
        "preview" => window.label() == "main",
        "reminder" => window.label() == "task-reminder",
        _ => false,
    };
    if !allowed { return Err("当前窗口不能控制此声音通道".into()); }
    if action == "supported" { return Ok(cfg!(target_os = "macos")); }
    #[cfg(target_os = "macos")]
    {
        if !matches!(channel.as_str(), "preview" | "reminder") { return Err("未知的声音通道".into()); }
        let data = if action == "play" {
            if id.starts_with("builtin:") {
                let bytes = bytes.ok_or("内置声音数据缺失")?;
                if bytes.len() > 1024 * 1024 || !bytes.starts_with(b"RIFF") || bytes.get(8..12) != Some(b"WAVE") {
                    return Err("内置声音数据无效".into());
                }
                Some(bytes)
            } else { Some(crate::task_reminder_settings::read_task_reminder_audio(app.clone(), id)?) }
        } else { None };
        let (send, receive) = std::sync::mpsc::channel();
        app.run_on_main_thread(move || {
            use objc2::AnyThread;
            use objc2_app_kit::NSSound;
            use objc2_foundation::NSData;
            let result = TONES.with(|cell| -> Result<bool, String> {
                let mut tones = cell.borrow_mut();
                match action.as_str() {
                    "play" => {
                        if let Some(sound) = tones.remove(&channel) { sound.stop(); }
                        let data = NSData::with_bytes(data.as_deref().ok_or("声音数据缺失")?);
                        let sound = NSSound::initWithData(NSSound::alloc(), &data).ok_or("无法播放此音频，请改用 WAV 或 MP3 文件")?;
                        if !sound.play() { return Err("提示音播放失败，请检查系统声音设置".into()); }
                        tones.insert(channel.clone(), sound);
                    }
                    "stop" => { if let Some(sound) = tones.remove(&channel) { sound.stop(); } }
                    "status" => {}
                    _ => return Err("未知的声音操作".into()),
                }
                Ok(tones.get(&channel).is_some_and(|sound| sound.isPlaying()))
            });
            if send.send(result).is_err() { log::debug!("声音请求已取消"); }
        }).map_err(|e| e.to_string())?;
        tauri::async_runtime::spawn_blocking(move || receive.recv_timeout(std::time::Duration::from_secs(5)).map_err(|e| e.to_string())?)
            .await.map_err(|e| e.to_string())?
    }
    #[cfg(not(target_os = "macos"))]
    { let _ = (app, id, channel, bytes); Err("当前平台使用音频文件播放".into()) }
}
