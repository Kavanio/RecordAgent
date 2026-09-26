#[cfg(desktop)]
mod backend;
#[cfg(desktop)]
mod system_settings;
#[cfg(target_os = "macos")]
mod notification_authorization;
#[cfg(desktop)]
mod task_desktop;
#[cfg(desktop)]
mod task_reminder_settings;
#[cfg(desktop)]
mod task_rest_lock;
#[cfg(desktop)]
mod task_reminder_audio;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  let builder = tauri::Builder::default()
    .plugin(tauri_plugin_shell::init())
    .plugin(tauri_plugin_dialog::init())
    .plugin(tauri_plugin_notification::init())
    .setup(|app| {
      if cfg!(debug_assertions) {
        app.handle().plugin(
          tauri_plugin_log::Builder::default()
            .level(log::LevelFilter::Info)
            .build(),
        )?;
      }
      #[cfg(desktop)]
      if !cfg!(debug_assertions) {
        backend::setup(app.handle())?;
      }
      #[cfg(desktop)]
      task_desktop::setup(app.handle())?;
      #[cfg(desktop)]
      task_rest_lock::setup(app.handle());
      #[cfg(target_os = "macos")]
      {
        use objc2::AnyThread;
        use objc2_app_kit::{NSApplication, NSImage};
        use objc2_foundation::NSData;
        let icon_bytes = include_bytes!("../icons/icon.png");
        let data = NSData::with_bytes(icon_bytes);
        if let Some(ns_image) = NSImage::initWithData(NSImage::alloc(), &data) {
          if let Some(mtm) = objc2::MainThreadMarker::new() {
            let ns_app = NSApplication::sharedApplication(mtm);
            unsafe {
              ns_app.setApplicationIconImage(Some(&ns_image));
            }
          }
        }
      }
      Ok(())
    });

  #[cfg(desktop)]
  let builder = builder.invoke_handler(tauri::generate_handler![
    backend::get_backend_mode,
    backend::get_backend_port,
    system_settings::open_location_settings,
    task_desktop::task_activity_status,
    task_desktop::send_task_notification,
    task_desktop::task_notification_status,
    task_desktop::request_task_notification_permission,
    task_desktop::sync_task_reminder,
    task_desktop::task_background_enabled,
    task_desktop::set_task_background,
    task_desktop::open_task_main,
    task_reminder_audio::task_reminder_audio,
    task_reminder_settings::task_reminder_configuration,
    task_reminder_settings::save_task_reminder_preferences,
    task_reminder_settings::import_task_reminder_audio,
    task_reminder_settings::read_task_reminder_audio,
    task_rest_lock::sync_task_rest_lock,
    task_rest_lock::preview_task_rest_lock
  ]);

  let app = builder
    .on_window_event(|window, event| {
      #[cfg(desktop)]
      if let tauri::WindowEvent::CloseRequested { api, .. } = event {
        use tauri::Manager;
        let background = window.app_handle().state::<task_desktop::TaskDesktopState>()
          .background.load(std::sync::atomic::Ordering::Relaxed);
        if window.label() == "task-reminder" || window.label().starts_with("task-rest-shield-") {
          api.prevent_close();
        } else if window.label() == "main" {
          api.prevent_close();
          if background {
            if let Err(e) = window.hide() { log::warn!("隐藏主窗口失败：{}", e); }
          } else {
            window.app_handle().exit(0);
          }
        }
      }
    })
    .build(tauri::generate_context!())
    .expect("error while building tauri application");

  app.run(|app_handle, event| {
    if let tauri::RunEvent::Exit = event {
      #[cfg(desktop)]
      backend::shutdown(app_handle);
    }
  });
}
