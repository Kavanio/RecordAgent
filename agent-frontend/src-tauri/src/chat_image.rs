use tauri::ipc::{InvokeBody, Request};
use tauri_plugin_dialog::DialogExt;

// 仅把位图写入本次系统保存面板选定的位置。
#[tauri::command]
pub async fn save_chat_image(app: tauri::AppHandle, request: Request<'_>) -> Result<bool, String> {
    let InvokeBody::Raw(bytes) = request.body() else {
        return Err("图片内容无效".into());
    };
    if bytes.len() > 10 * 1024 * 1024 {
        return Err("图片超过 10 MiB".into());
    }
    let extension = image_extension(bytes).ok_or("图片格式无效")?;
    let bytes = bytes.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let path = app
            .dialog()
            .file()
            .set_title("保存聊天图片")
            .set_file_name(format!("chat-image.{extension}"))
            .add_filter("图片", &[extension])
            .blocking_save_file();
        let Some(path) = path else { return Ok(false) };
        let path = path.into_path().map_err(|_| "无法访问保存位置")?;
        std::fs::write(path, bytes).map_err(|_| "无法写入所选文件")?;
        Ok(true)
    })
    .await
    .map_err(|_| "保存图片失败")?
}

fn image_extension(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("png")
    } else if bytes.starts_with(&[0xff, 0xd8, 0xff]) {
        Some("jpg")
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Some("gif")
    } else if bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WEBP") {
        Some("webp")
    } else {
        None
    }
}
