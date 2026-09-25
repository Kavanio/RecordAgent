//! macOS 通知授权：保留系统错误，避免将应用签名错误误报为用户拒绝。

use block2::RcBlock;
use objc2::runtime::Bool;
use objc2_foundation::NSError;
use objc2_user_notifications::{UNAuthorizationOptions, UNUserNotificationCenter};
use std::{sync::mpsc, time::Duration};

/// 在后台线程等待系统授权回调；主线程必须继续处理原生弹窗。
/// mac-usernotifications 0.3.1 的授权封装忽略 NSError，因此这里直接调用系统 API。
pub fn request() -> Result<bool, String> {
    mac_usernotifications::check_bundle()
        .map_err(|_| "无法识别应用身份，请从“应用程序”中打开完整安装的 App。".to_string())?;
    let (sender, receiver) = mpsc::sync_channel(1);
    UNUserNotificationCenter::currentNotificationCenter()
        .requestAuthorizationWithOptions_completionHandler(
            UNAuthorizationOptions::Alert | UNAuthorizationOptions::Sound,
            &RcBlock::new(move |granted: Bool, error: *mut NSError| {
                // NSError 仅在回调期间借用，将所需信息复制为 Rust 字符串。
                let result = match unsafe { error.as_ref() } {
                    Some(error) => {
                        log::warn!(
                            "通知授权请求失败：{} ({})：{}",
                            error.domain(),
                            error.code(),
                            error.localizedDescription()
                        );
                        Err(format!(
                            "系统通知授权请求失败（{} / {}），请检查应用安装与签名后重试。",
                            error.domain(),
                            error.code()
                        ))
                    }
                    None => Ok(granted.as_bool()),
                };
                if sender.send(result).is_err() {
                    log::warn!("通知授权回调到达时请求已结束");
                }
            }),
        );
    receiver
        .recv_timeout(Duration::from_secs(120))
        .map_err(|_| "等待系统通知授权超时，请完成系统弹窗后重试。".to_string())?
}
