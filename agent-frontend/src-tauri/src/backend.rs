//! 后端 sidecar 生命周期管理
//!
//! 负责在应用启动时拉起 Spring Boot 后端、等待健康检查通过，
//! 将动态分配的端口暴露给前端，并在应用退出时关闭后端进程。

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

use log::{error, info, warn};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, State};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

const CONNECTION_FILE: &str = "backend-connection.json";
const KEYRING_SERVICE: &str = "com.butvan.agent";
const KEYRING_USER: &str = "remote-backend-api-token";

/// 本机配置的远程后端地址；不包含业务数据或凭据。
#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendConnectionConfig {
    pub remote_url: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendConnectionInfo {
    pub mode: String,
    pub remote_url: Option<String>,
    pub has_api_token: bool,
    pub saved_remote_url: Option<String>,
    pub restart_required: bool,
}

/// 后端进程与端口共享状态（通过 Tauri 状态管理注入）
pub struct BackendState {
    /// 健康检查通过后写入端口，前端通过 `get_backend_port` 命令读取
    pub port: Mutex<Option<u16>>,
    /// sidecar 子进程句柄，应用退出时用于关闭后端
    pub child: Mutex<Option<CommandChild>>,
    /// 远程连接和本地 sidecar 互斥，避免双重执行自动任务。
    pub mode: String,
    /// 启动时加载的远程 API 地址。
    pub remote_url: Option<String>,
    /// 远程健康检查超时后，标记本次启动已切换到本机后端。
    pub local_fallback: AtomicBool,
    /// 已保存的连接配置变化，等待重启后生效。
    pub restart_required: AtomicBool,
}

impl Default for BackendState {
    fn default() -> Self {
        Self {
            port: Mutex::new(None),
            child: Mutex::new(None),
            mode: if cfg!(debug_assertions) {
                "dev".into()
            } else {
                "sidecar".into()
            },
            remote_url: None,
            local_fallback: AtomicBool::new(false),
            restart_required: AtomicBool::new(false),
        }
    }
}

/// 前端查询当前后端运行模式：dev、sidecar 或 remote。
#[tauri::command]
pub fn get_backend_mode(state: State<'_, BackendState>) -> String {
    resolved_mode(&state)
}

/// 返回当前启动模式和远程地址，供桌面设置页显示。
#[tauri::command]
pub fn get_backend_connection(
    app: AppHandle,
    state: State<'_, BackendState>,
) -> Result<BackendConnectionInfo, String> {
    let saved = load_connection(&app)?;
    Ok(BackendConnectionInfo {
        mode: resolved_mode(&state),
        remote_url: state.remote_url.clone(),
        has_api_token: read_api_token()
            .map_err(|e| format!("读取系统钥匙串失败：{e}"))?
            .is_some(),
        saved_remote_url: saved.remote_url,
        restart_required: state.restart_required.load(Ordering::Relaxed),
    })
}

/// 使用 Tauri 的正常退出流程重启，确保退出事件先清理本机 sidecar。
#[tauri::command]
pub fn restart_application(app: AppHandle) {
    app.request_restart();
}

fn resolved_mode(state: &BackendState) -> String {
    if state.local_fallback.load(Ordering::Relaxed) {
        if cfg!(debug_assertions) {
            "dev"
        } else {
            "sidecar"
        }
        .into()
    } else {
        state.mode.clone()
    }
}

/// 保存远程后端地址；模式在重启后切换，以保证不会同时运行两套调度器。
#[tauri::command]
pub fn save_remote_backend_url(
    app: AppHandle,
    remote_url: Option<String>,
    api_token: Option<String>,
) -> Result<bool, String> {
    let cleaned = remote_url
        .map(|url| url.trim().trim_end_matches('/').to_string())
        .filter(|url| !url.is_empty());
    let previous = load_connection(&app)?;
    let previous_token = read_api_token().map_err(|e| format!("读取系统钥匙串失败：{e}"))?;
    let token = api_token.as_deref().unwrap_or_default().trim();
    let changed = previous.remote_url != cleaned
        || (cleaned.is_some() && previous_token.as_deref() != Some(token));
    if !changed {
        return Ok(false);
    }
    if let Some(url) = &cleaned {
        validate_remote_url(url)?;
        if token.len() < 32 {
            return Err("远程模式需要至少 32 个字符的 API Token".into());
        }
        keyring_entry()?
            .set_password(token)
            .map_err(|e| format!("无法将 API Token 保存到系统钥匙串：{e}"))?;
    } else {
        match keyring_entry()?.delete_credential() {
            Ok(()) => {}
            Err(keyring::Error::NoEntry) => {}
            Err(e) => return Err(format!("无法从系统钥匙串删除 API Token：{e}")),
        }
    }
    let path = connection_path(&app)?;
    let parent = path.parent().ok_or("无法确定应用配置目录")?;
    std::fs::create_dir_all(parent).map_err(|e| format!("创建应用配置目录失败：{e}"))?;
    let bytes = serde_json::to_vec(&BackendConnectionConfig {
        remote_url: cleaned,
    })
    .map_err(|e| e.to_string())?;
    let temp = path.with_extension("json.tmp");
    std::fs::write(&temp, bytes).map_err(|e| format!("写入后端连接配置失败：{e}"))?;
    std::fs::rename(temp, path).map_err(|e| format!("保存后端连接配置失败：{e}"))?;
    app.state::<BackendState>()
        .restart_required
        .store(true, Ordering::Relaxed);
    Ok(true)
}

/// 将令牌从系统钥匙串读取到前端内存，仅用于构造本次 API 请求头。
#[tauri::command]
pub fn get_backend_api_token() -> Result<Option<String>, String> {
    read_api_token().map_err(|e| format!("读取系统钥匙串失败：{e}"))
}

fn keyring_entry() -> Result<keyring::Entry, String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(|e| e.to_string())
}

fn read_api_token() -> Result<Option<String>, keyring::Error> {
    match keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER)?.get_password() {
        Ok(token) => Ok(Some(token)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(error),
    }
}

fn validate_remote_url(url: &str) -> Result<(), String> {
    let (scheme, rest) = url
        .split_once("://")
        .ok_or("请输入完整的后端地址，例如 http://192.168.1.10:8081")?;
    let authority = rest.split('/').next().unwrap_or_default();
    let base_path = &rest[authority.len()..];
    if !matches!(scheme, "http" | "https")
        || authority.is_empty()
        || !matches!(base_path, "" | "/")
        || authority.contains('@')
        || authority.contains('?')
        || authority.contains('#')
        || rest.contains('?')
        || rest.contains('#')
        || url.chars().any(char::is_whitespace)
    {
        return Err("后端地址仅支持不含账号、查询参数或片段的 HTTP/HTTPS 地址".into());
    }
    Ok(())
}

/// 连接配置与业务数据同寿命，避免重装应用后远程地址丢失。
fn connection_path(app: &AppHandle) -> Result<PathBuf, String> {
    crate::desktop_preferences::root(app).map(|dir| dir.join(CONNECTION_FILE))
}

fn load_connection(app: &AppHandle) -> Result<BackendConnectionConfig, String> {
    match std::fs::read(connection_path(app)?) {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(|e| format!("后端连接配置损坏：{e}")),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            Ok(BackendConnectionConfig::default())
        }
        Err(e) => Err(format!("读取后端连接配置失败：{e}")),
    }
}

/// 前端查询后端动态端口；未就绪时返回 null
#[tauri::command]
pub fn get_backend_port(state: State<'_, BackendState>) -> Option<u16> {
    *state.port.lock().unwrap()
}

/// 远程服务连接超时后，启动本地后端作为本次运行的降级目标。
#[tauri::command]
pub fn start_local_backend_fallback(app: AppHandle) -> Result<(), String> {
    let state = app.state::<BackendState>();
    state.local_fallback.store(true, Ordering::Relaxed);
    if cfg!(debug_assertions) {
        info!("远程后端连接超时，开发模式回退到本机 8081 端口");
        return Ok(());
    }
    if state.port.lock().unwrap().is_some() || state.child.lock().unwrap().is_some() {
        return Ok(());
    }
    start_sidecar(&app).map(|_| ())
}

/// 在应用 setup 阶段读取远程模式；本机模式按需启动 sidecar 并等待健康检查。
pub fn setup(app: &AppHandle) -> Result<(), Box<dyn std::error::Error>> {
    let connection = load_connection(app).map_err(std::io::Error::other)?;
    if let Some(url) = connection.remote_url {
        validate_remote_url(&url).map_err(std::io::Error::other)?;
        app.manage(BackendState {
            mode: "remote".into(),
            remote_url: Some(url),
            ..BackendState::default()
        });
        info!("使用已配置的远程 Agent 后端");
        return Ok(());
    }
    let mode = if cfg!(debug_assertions) {
        "dev"
    } else {
        "sidecar"
    };
    app.manage(BackendState {
        mode: mode.into(),
        ..BackendState::default()
    });
    if cfg!(debug_assertions) {
        return Ok(());
    }

    start_sidecar(app).map_err(std::io::Error::other)?;
    Ok(())
}

/// 启动本地 sidecar，并在后台发布健康检查通过后的动态端口。
fn start_sidecar(app: &AppHandle) -> Result<u16, String> {
    let port = pick_free_port();
    info!("分配后端动态端口：{port}");

    let sidecar = app
        .shell()
        .sidecar("butvan-backend")
        .map_err(|e| e.to_string())?;
    let (mut rx, child) = sidecar
        .args([
            format!("--server.port={port}"),
            "--server.address=127.0.0.1".to_string(),
        ])
        .spawn()
        .map_err(|e| format!("后端 sidecar 启动失败：{e}"))?;

    let state = app.state::<BackendState>();
    *state.child.lock().unwrap() = Some(child);

    // 后台线程轮询 /api/health，就绪后把端口写入状态供前端读取
    let handle = app.clone();
    thread::spawn(move || {
        let ready = wait_until_ready(port, Duration::from_secs(60));
        if ready {
            if let Some(state) = handle.try_state::<BackendState>() {
                *state.port.lock().unwrap() = Some(port);
            }
            info!("后端 sidecar 已就绪，端口：{port}");
        } else {
            error!("后端 sidecar 60 秒内未就绪，端口：{port}");
        }
    });

    // 转发子进程标准输出/错误到应用日志，避免管道积压阻塞
    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            match event {
                CommandEvent::Stdout(line) | CommandEvent::Stderr(line) => {
                    let text = String::from_utf8_lossy(&line);
                    let text = text.trim_end();
                    if !text.is_empty() {
                        info!("[backend] {text}");
                    }
                }
                CommandEvent::Terminated(payload) => {
                    warn!("后端 sidecar 已退出：code={:?}", payload.code);
                }
                _ => {}
            }
        }
    });

    Ok(port)
}

/// 应用退出时关闭后端进程
pub fn shutdown(app: &AppHandle) {
    if let Some(state) = app.try_state::<BackendState>() {
        if let Some(child) = state.child.lock().unwrap().take() {
            let _ = child.kill();
            info!("后端 sidecar 已随应用退出关闭");
        }
    }
}

/// 在 127.0.0.1 上分配一个空闲端口
fn pick_free_port() -> u16 {
    let listener = TcpListener::bind(("127.0.0.1", 0)).expect("无法分配空闲端口");
    listener.local_addr().expect("读取端口失败").port()
}

/// 轮询后端健康检查接口，直到返回 HTTP 200 或超时
fn wait_until_ready(port: u16, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if probe_health(port) {
            return true;
        }
        thread::sleep(Duration::from_millis(400));
    }
    false
}

/// 向 /api/health 发送最小 HTTP GET 请求，返回是否收到 200 响应
fn probe_health(port: u16) -> bool {
    let Ok(addr) = format!("127.0.0.1:{port}").parse::<std::net::SocketAddr>() else {
        return false;
    };
    let Ok(mut stream) = TcpStream::connect_timeout(&addr, Duration::from_millis(400)) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(400)));

    let request =
        format!("GET /api/health HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n");
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }

    let mut buf = [0u8; 256];
    let Ok(n) = stream.read(&mut buf) else {
        return false;
    };
    String::from_utf8_lossy(&buf[..n]).starts_with("HTTP/1.1 200")
}
