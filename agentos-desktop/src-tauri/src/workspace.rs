use base64::Engine;
use portable_pty::{native_pty_system, CommandBuilder, MasterPty, PtySize};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::ffi::OsStr;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager, State, WebviewWindow};
use tauri_plugin_dialog::DialogExt;
use uuid::Uuid;

const GRANT_TTL: Duration = Duration::from_secs(60);
const FILE_LIMIT: u64 = 1024 * 1024;
const DIFF_BYTE_LIMIT: usize = 2 * 1024 * 1024;
const DIFF_LINE_LIMIT: usize = 20_000;
const DIRECTORY_ENTRY_LIMIT: usize = 1_000;
const CONTEXT_TREE_DEPTH_LIMIT: usize = 5;
const CONTEXT_TREE_ENTRY_LIMIT: usize = 400;
const WORKSPACE_FILE_INDEX_LIMIT: usize = 5_000;
const WORKSPACE_FILE_INDEX_DEPTH_LIMIT: usize = 20;
const CONTEXT_FILE_LIMIT: usize = 8;
const CONTEXT_FILE_BYTE_LIMIT: usize = 12 * 1024;
const CONTEXT_TOTAL_FILE_BYTES: usize = 28 * 1024;

#[derive(Clone)]
struct Grant {
    window_label: String,
    user: String,
    expires_at: Instant,
}

#[derive(Clone)]
struct WorkspaceEntry {
    id: String,
    root: PathBuf,
    last_opened_at: u64,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct PersistedWorkspace {
    id: String,
    root: String,
    last_opened_at: u64,
}

struct TerminalNative {
    grant_id: String,
    window_label: String,
    workspace_id: String,
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    child: Arc<Mutex<Box<dyn portable_pty::Child + Send + Sync>>>,
}

struct WorkspaceInner {
    grants: HashMap<String, Grant>,
    workspaces: HashMap<String, WorkspaceEntry>,
    terminals: HashMap<String, TerminalNative>,
}

pub struct WorkspaceState {
    inner: Mutex<WorkspaceInner>,
    storage_file: PathBuf,
}

impl WorkspaceState {
    pub fn new(storage_file: PathBuf) -> Self {
        let mut workspaces = HashMap::new();
        if let Ok(raw) = fs::read_to_string(&storage_file) {
            if let Ok(entries) = serde_json::from_str::<Vec<PersistedWorkspace>>(&raw) {
                for persisted in entries {
                    if let Ok(root) = PathBuf::from(persisted.root).canonicalize() {
                        if root.is_dir() {
                            let id = persisted.id;
                            workspaces.insert(
                                id.clone(),
                                WorkspaceEntry {
                                    id,
                                    root,
                                    last_opened_at: persisted.last_opened_at,
                                },
                            );
                        }
                    }
                }
            } else if let Ok(paths) = serde_json::from_str::<Vec<String>>(&raw) {
                // Migrate the first desktop preview format, which stored paths only.
                for raw_path in paths {
                    if let Ok(root) = PathBuf::from(raw_path).canonicalize() {
                        if root.is_dir() {
                            let id = Uuid::new_v4().to_string();
                            workspaces.insert(
                                id.clone(),
                                WorkspaceEntry {
                                    id,
                                    root,
                                    last_opened_at: now_millis(),
                                },
                            );
                        }
                    }
                }
            }
        }
        Self {
            inner: Mutex::new(WorkspaceInner {
                grants: HashMap::new(),
                workspaces,
                terminals: HashMap::new(),
            }),
            storage_file,
        }
    }

    fn require_grant(&self, grant_id: &str, window_label: &str) -> Result<Grant, String> {
        let inner = self
            .inner
            .lock()
            .map_err(|_| "工作区状态不可用".to_string())?;
        let grant = inner
            .grants
            .get(grant_id)
            .ok_or_else(|| "工作区授权不存在，请重新验证".to_string())?;
        if grant.window_label != window_label {
            return Err("工作区授权不属于当前窗口".to_string());
        }
        if grant.expires_at <= Instant::now() {
            return Err("工作区授权已过期，请重新验证".to_string());
        }
        Ok(grant.clone())
    }

    fn workspace(&self, workspace_id: &str) -> Result<WorkspaceEntry, String> {
        self.inner
            .lock()
            .map_err(|_| "工作区状态不可用".to_string())?
            .workspaces
            .get(workspace_id)
            .cloned()
            .ok_or_else(|| "工作区不存在或目录已失效".to_string())
    }

    fn persist(&self, inner: &WorkspaceInner) -> Result<(), String> {
        let mut entries = inner
            .workspaces
            .values()
            .map(|entry| PersistedWorkspace {
                id: entry.id.clone(),
                root: entry.root.to_string_lossy().to_string(),
                last_opened_at: entry.last_opened_at,
            })
            .collect::<Vec<_>>();
        entries.sort_by(|left, right| left.id.cmp(&right.id));
        let encoded = serde_json::to_vec_pretty(&entries).map_err(|error| error.to_string())?;
        fs::write(&self.storage_file, encoded)
            .map_err(|error| format!("无法保存最近工作区：{error}"))
    }

    fn close_terminal_locked(terminal: &mut TerminalNative) {
        if let Ok(mut child) = terminal.child.lock() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }

    fn revoke_grant_locked(inner: &mut WorkspaceInner, grant_id: &str) {
        inner.grants.remove(grant_id);
        let ids = inner
            .terminals
            .iter()
            .filter(|(_, terminal)| terminal.grant_id == grant_id)
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        for id in ids {
            if let Some(mut terminal) = inner.terminals.remove(&id) {
                Self::close_terminal_locked(&mut terminal);
            }
        }
    }

    pub fn cleanup_window(&self, window_label: &str) {
        let Ok(mut inner) = self.inner.lock() else {
            return;
        };
        let grant_ids = inner
            .grants
            .iter()
            .filter(|(_, grant)| grant.window_label == window_label)
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        for grant_id in grant_ids {
            Self::revoke_grant_locked(&mut inner, &grant_id);
        }
    }

    fn reap_expired(&self) {
        let Ok(mut inner) = self.inner.lock() else {
            return;
        };
        let expired = inner
            .grants
            .iter()
            .filter(|(_, grant)| grant.expires_at <= Instant::now())
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        for grant_id in expired {
            Self::revoke_grant_locked(&mut inner, &grant_id);
        }
    }
}

#[derive(Deserialize)]
struct MeResponse {
    username: String,
    #[serde(default)]
    roles: Vec<String>,
}

fn can_access_workspace(roles: &[String]) -> bool {
    roles
        .iter()
        .any(|role| role.eq_ignore_ascii_case("WORKSPACE") || role.eq_ignore_ascii_case("ADMIN"))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceGrant {
    grant_id: String,
    user: String,
    expires_at: u64,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceSummary {
    id: String,
    name: String,
    root: String,
    git_repository: bool,
    last_opened_at: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    name: String,
    relative_path: String,
    kind: String,
    size: u64,
    modified_at: Option<u64>,
    traversable: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileContent {
    relative_path: String,
    content: Option<String>,
    binary: bool,
    truncated: bool,
    size: u64,
    language: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceContextFile {
    path: String,
    content: String,
    truncated: bool,
    mentioned: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceContext {
    name: String,
    tree: Vec<String>,
    files: Vec<WorkspaceContextFile>,
    truncated: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceFileReference {
    name: String,
    relative_path: String,
    language: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadedAttachment {
    name: String,
    relative_path: String,
    size: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitStatusEntry {
    path: String,
    old_path: Option<String>,
    index_status: String,
    worktree_status: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitStatus {
    repository: String,
    branch: String,
    upstream: Option<String>,
    ahead: u64,
    behind: u64,
    entries: Vec<GitStatusEntry>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitDiff {
    scope: String,
    path: Option<String>,
    patch: String,
    additions: usize,
    deletions: usize,
    truncated: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TerminalSession {
    id: String,
    workspace_id: String,
    shell: String,
    cwd: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct TerminalOutputEvent {
    session_id: String,
    data_base64: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct TerminalExitEvent {
    session_id: String,
    exit_code: Option<u32>,
}

#[tauri::command]
pub async fn authorize_workspace(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    server_url: String,
    token: String,
) -> Result<WorkspaceGrant, String> {
    let base = reqwest::Url::parse(server_url.trim())
        .map_err(|_| "Server 地址必须是有效的 http/https URL".to_string())?;
    if !matches!(base.scheme(), "http" | "https") {
        return Err("Server 地址只支持 http/https".to_string());
    }
    if token.trim().is_empty() {
        return Err("登录凭据为空".to_string());
    }
    let endpoint = base
        .join("/api/auth/me")
        .map_err(|error| format!("无法构造授权地址：{error}"))?;
    let response = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()
        .map_err(|error| error.to_string())?
        .get(endpoint)
        .bearer_auth(token.trim())
        .send()
        .await
        .map_err(|error| format!("无法验证工作区权限：{error}"))?;
    if !response.status().is_success() {
        return Err(if response.status().as_u16() == 401 {
            "登录已失效，请重新登录".to_string()
        } else {
            format!("工作区权限验证失败：HTTP {}", response.status().as_u16())
        });
    }
    let me = response
        .json::<MeResponse>()
        .await
        .map_err(|error| format!("工作区权限响应无效：{error}"))?;
    if !can_access_workspace(&me.roles) {
        return Err("当前账户缺少 WORKSPACE 或 ADMIN 角色".to_string());
    }
    let expires_at = Instant::now() + GRANT_TTL;
    let expires_at_epoch = now_millis() + GRANT_TTL.as_millis() as u64;
    let mut inner = state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?;
    let window_label = window.label().to_string();
    let grant_id = inner
        .grants
        .iter_mut()
        .find_map(|(id, grant)| {
            if grant.window_label == window_label && grant.user == me.username {
                grant.expires_at = expires_at;
                Some(id.clone())
            } else {
                None
            }
        })
        .unwrap_or_else(|| {
            let id = Uuid::new_v4().to_string();
            inner.grants.insert(
                id.clone(),
                Grant {
                    window_label,
                    user: me.username.clone(),
                    expires_at,
                },
            );
            id
        });
    Ok(WorkspaceGrant {
        grant_id,
        user: me.username,
        expires_at: expires_at_epoch,
    })
}

#[tauri::command]
pub fn revoke_workspace(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
) -> Result<(), String> {
    state.require_grant(&grant_id, window.label())?;
    let mut inner = state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?;
    WorkspaceState::revoke_grant_locked(&mut inner, &grant_id);
    Ok(())
}

#[tauri::command]
pub async fn pick_workspace(
    app: AppHandle,
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
) -> Result<Option<WorkspaceSummary>, String> {
    state.require_grant(&grant_id, window.label())?;
    let picked = app.dialog().file().blocking_pick_folder();
    let Some(picked) = picked else {
        return Ok(None);
    };
    let root = picked
        .into_path()
        .map_err(|error| format!("无法读取所选目录：{error}"))?
        .canonicalize()
        .map_err(|error| format!("无法打开所选目录：{error}"))?;
    if !root.is_dir() {
        return Err("所选路径不是目录".to_string());
    }
    let mut inner = state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?;
    if let Some(existing) = inner
        .workspaces
        .values_mut()
        .find(|entry| entry.root == root)
    {
        existing.last_opened_at = now_millis();
        let summary = workspace_summary(existing);
        state.persist(&inner)?;
        return Ok(Some(summary));
    }
    let id = Uuid::new_v4().to_string();
    let entry = WorkspaceEntry {
        id: id.clone(),
        root,
        last_opened_at: now_millis(),
    };
    let summary = workspace_summary(&entry);
    inner.workspaces.insert(id, entry);
    state.persist(&inner)?;
    Ok(Some(summary))
}

#[tauri::command]
pub fn list_workspaces(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
) -> Result<Vec<WorkspaceSummary>, String> {
    state.require_grant(&grant_id, window.label())?;
    let mut values = state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?
        .workspaces
        .values()
        .map(workspace_summary)
        .collect::<Vec<_>>();
    values.sort_by(|a, b| b.last_opened_at.cmp(&a.last_opened_at));
    Ok(values)
}

#[tauri::command]
pub async fn upload_attachments(
    app: AppHandle,
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
) -> Result<Vec<UploadedAttachment>, String> {
    state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    let Some(picked) = app.dialog().file().blocking_pick_files() else {
        return Ok(Vec::new());
    };
    let mut uploaded = Vec::with_capacity(picked.len());
    for selected in picked {
        let source = selected
            .into_path()
            .map_err(|error| format!("无法读取所选附件：{error}"))?
            .canonicalize()
            .map_err(|error| format!("无法打开所选附件：{error}"))?;
        if !source.is_file() {
            return Err("附件必须是文件".to_string());
        }
        let file_name = source
            .file_name()
            .and_then(OsStr::to_str)
            .filter(|name| !name.is_empty())
            .ok_or_else(|| "附件名称无效".to_string())?;
        let destination = if source.parent() == Some(workspace.root.as_path()) {
            source.clone()
        } else {
            unique_attachment_path(&workspace.root, file_name)
        };
        if source != destination {
            fs::copy(&source, &destination)
                .map_err(|error| format!("无法上传附件 {file_name}：{error}"))?;
        }
        let size = destination
            .metadata()
            .map_err(|error| format!("无法读取附件信息：{error}"))?
            .len();
        let stored_name = destination
            .file_name()
            .and_then(OsStr::to_str)
            .unwrap_or(file_name)
            .to_string();
        uploaded.push(UploadedAttachment {
            name: stored_name.clone(),
            relative_path: stored_name,
            size,
        });
    }
    Ok(uploaded)
}

#[tauri::command]
pub fn forget_workspace(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
) -> Result<(), String> {
    state.require_grant(&grant_id, window.label())?;
    let mut inner = state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?;
    let terminal_ids = inner
        .terminals
        .iter()
        .filter(|(_, terminal)| terminal.workspace_id == workspace_id)
        .map(|(id, _)| id.clone())
        .collect::<Vec<_>>();
    for id in terminal_ids {
        if let Some(mut terminal) = inner.terminals.remove(&id) {
            WorkspaceState::close_terminal_locked(&mut terminal);
        }
    }
    inner.workspaces.remove(&workspace_id);
    state.persist(&inner)
}

#[tauri::command]
pub fn list_directory(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
    relative_path: String,
) -> Result<Vec<FileEntry>, String> {
    state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    let directory = resolve_existing(&workspace.root, &relative_path)?;
    if !directory.is_dir() {
        return Err("目标不是目录".to_string());
    }
    let mut entries = fs::read_dir(&directory)
        .map_err(|error| format!("无法读取目录：{error}"))?
        .filter_map(Result::ok)
        .filter(|entry| !excluded(entry.file_name().as_os_str()))
        .take(DIRECTORY_ENTRY_LIMIT)
        .filter_map(|entry| file_entry(&workspace.root, entry).ok())
        .collect::<Vec<_>>();
    entries.sort_by(|a, b| {
        let a_dir = a.kind == "directory";
        let b_dir = b.kind == "directory";
        b_dir
            .cmp(&a_dir)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    Ok(entries)
}

#[tauri::command]
pub fn read_file(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
    relative_path: String,
) -> Result<FileContent, String> {
    state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    let path = resolve_existing(&workspace.root, &relative_path)?;
    if !path.is_file() {
        return Err("目标不是文件".to_string());
    }
    read_file_content(&path, relative_path)
}

/// 为远端 Agent 构造有界、只读的本地项目上下文。
///
/// 只收集非隐藏目录树、README、构建清单和常见入口文件；不返回绝对路径，
/// 也不会读取 .env、隐藏目录、构建产物或任意二进制文件。
#[tauri::command]
pub fn workspace_context(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
    mentioned_paths: Option<Vec<String>>,
) -> Result<WorkspaceContext, String> {
    state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    build_workspace_context(&workspace, mentioned_paths.as_deref().unwrap_or_default())
}

/// 返回可由输入框 `@` 菜单引用的项目文本文件索引。
#[tauri::command]
pub fn workspace_file_index(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
) -> Result<Vec<WorkspaceFileReference>, String> {
    state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    build_workspace_file_index(&workspace.root)
}

fn read_file_content(path: &Path, relative_path: String) -> Result<FileContent, String> {
    let size = path.metadata().map_err(|error| error.to_string())?.len();
    let mut bytes = Vec::new();
    File::open(&path)
        .map_err(|error| format!("无法打开文件：{error}"))?
        .take(FILE_LIMIT + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("无法读取文件：{error}"))?;
    let truncated = bytes.len() as u64 > FILE_LIMIT;
    bytes.truncate(FILE_LIMIT as usize);
    let binary = bytes.contains(&0) || std::str::from_utf8(&bytes).is_err();
    let content = if binary {
        None
    } else {
        Some(String::from_utf8_lossy(&bytes).to_string())
    };
    Ok(FileContent {
        relative_path,
        content,
        binary,
        truncated,
        size,
        language: language_for(&path),
    })
}

fn build_workspace_context(
    workspace: &WorkspaceEntry,
    mentioned_paths: &[String],
) -> Result<WorkspaceContext, String> {
    let mut tree = Vec::new();
    let mut candidates = Vec::new();
    let mut truncated = false;
    collect_workspace_context(
        &workspace.root,
        &workspace.root,
        0,
        &mut tree,
        &mut candidates,
        &mut truncated,
    )?;

    let mut prioritized = Vec::new();
    for relative_path in mentioned_paths.iter().take(CONTEXT_FILE_LIMIT) {
        let relative = validate_context_relative(relative_path)?;
        let source_path = workspace.root.join(&relative);
        let metadata = fs::symlink_metadata(&source_path)
            .map_err(|error| format!("无法读取 @ 文件 {relative_path}：{error}"))?;
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Err(format!("@ 引用不是可读取的项目文件：{relative_path}"));
        }
        let path = resolve_existing(&workspace.root, relative_path)?;
        prioritized.push((0, path, true));
    }
    prioritized.extend(
        candidates
            .into_iter()
            .map(|(priority, path)| (priority.saturating_add(10), path, false)),
    );
    prioritized.sort_by(|left, right| {
        left.0
            .cmp(&right.0)
            .then_with(|| left.1.to_string_lossy().cmp(&right.1.to_string_lossy()))
    });
    let mut seen = HashSet::new();
    prioritized.retain(|(_, path, _)| seen.insert(path.clone()));
    if prioritized.len() > CONTEXT_FILE_LIMIT {
        truncated = true;
    }

    let mut files = Vec::new();
    let mut remaining = CONTEXT_TOTAL_FILE_BYTES;
    for (_, path, mentioned) in prioritized.into_iter().take(CONTEXT_FILE_LIMIT) {
        if remaining == 0 {
            truncated = true;
            break;
        }
        let relative = path
            .strip_prefix(&workspace.root)
            .map_err(|_| "项目上下文文件越过工作区边界".to_string())?
            .to_string_lossy()
            .to_string();
        let limit = remaining.min(CONTEXT_FILE_BYTE_LIMIT);
        let mut bytes = Vec::new();
        File::open(&path)
            .map_err(|error| format!("无法读取项目上下文 {relative}：{error}"))?
            .take(limit as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|error| format!("无法读取项目上下文 {relative}：{error}"))?;
        if bytes.contains(&0) {
            continue;
        }
        let file_truncated = bytes.len() > limit;
        bytes.truncate(limit);
        let content = String::from_utf8_lossy(&bytes).to_string();
        remaining = remaining.saturating_sub(bytes.len());
        truncated |= file_truncated;
        files.push(WorkspaceContextFile {
            path: relative,
            content,
            truncated: file_truncated,
            mentioned,
        });
    }

    Ok(WorkspaceContext {
        name: workspace
            .root
            .file_name()
            .and_then(OsStr::to_str)
            .unwrap_or("Workspace")
            .to_string(),
        tree,
        files,
        truncated,
    })
}

fn build_workspace_file_index(root: &Path) -> Result<Vec<WorkspaceFileReference>, String> {
    let mut files = Vec::new();
    collect_workspace_file_index(root, root, 0, &mut files)?;
    files.sort_by(|left, right| {
        left.relative_path
            .to_lowercase()
            .cmp(&right.relative_path.to_lowercase())
    });
    Ok(files)
}

fn collect_workspace_file_index(
    root: &Path,
    directory: &Path,
    depth: usize,
    files: &mut Vec<WorkspaceFileReference>,
) -> Result<(), String> {
    if depth > WORKSPACE_FILE_INDEX_DEPTH_LIMIT || files.len() >= WORKSPACE_FILE_INDEX_LIMIT {
        return Ok(());
    }
    let mut entries = fs::read_dir(directory)
        .map_err(|error| format!("无法扫描项目文件：{error}"))?
        .filter_map(Result::ok)
        .collect::<Vec<_>>();
    entries.sort_by_key(|entry| entry.file_name().to_string_lossy().to_lowercase());
    for entry in entries {
        if files.len() >= WORKSPACE_FILE_INDEX_LIMIT {
            break;
        }
        let name = entry.file_name();
        if name.to_string_lossy().starts_with('.') || excluded(&name) {
            continue;
        }
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path).map_err(|error| error.to_string())?;
        if metadata.file_type().is_symlink() {
            continue;
        }
        if metadata.is_dir() {
            collect_workspace_file_index(root, &path, depth + 1, files)?;
        } else if metadata.is_file() && is_probably_text_file(&path) {
            let relative_path = path
                .strip_prefix(root)
                .map_err(|_| "项目文件越过工作区边界".to_string())?
                .to_string_lossy()
                .to_string();
            files.push(WorkspaceFileReference {
                name: name.to_string_lossy().to_string(),
                relative_path,
                language: language_for(&path),
            });
        }
    }
    Ok(())
}

fn validate_context_relative(value: &str) -> Result<PathBuf, String> {
    let relative = validate_relative(value)?;
    if relative.components().any(|component| match component {
        Component::Normal(name) => name.to_string_lossy().starts_with('.') || excluded(name),
        _ => false,
    }) {
        return Err("@ 文件不能引用隐藏文件或构建产物".to_string());
    }
    Ok(relative)
}

fn is_probably_text_file(path: &Path) -> bool {
    !matches!(
        path.extension()
            .and_then(OsStr::to_str)
            .unwrap_or_default()
            .to_ascii_lowercase()
            .as_str(),
        "png"
            | "jpg"
            | "jpeg"
            | "gif"
            | "webp"
            | "ico"
            | "pdf"
            | "doc"
            | "docx"
            | "xls"
            | "xlsx"
            | "ppt"
            | "pptx"
            | "zip"
            | "gz"
            | "tar"
            | "jar"
            | "class"
            | "woff"
            | "woff2"
            | "ttf"
            | "otf"
            | "mp3"
            | "mp4"
            | "mov"
            | "avi"
            | "dmg"
            | "exe"
            | "dll"
            | "so"
            | "dylib"
    )
}

fn collect_workspace_context(
    root: &Path,
    directory: &Path,
    depth: usize,
    tree: &mut Vec<String>,
    candidates: &mut Vec<(u8, PathBuf)>,
    truncated: &mut bool,
) -> Result<(), String> {
    if depth > CONTEXT_TREE_DEPTH_LIMIT || tree.len() >= CONTEXT_TREE_ENTRY_LIMIT {
        *truncated = true;
        return Ok(());
    }
    let mut entries = fs::read_dir(directory)
        .map_err(|error| format!("无法扫描项目目录：{error}"))?
        .filter_map(Result::ok)
        .collect::<Vec<_>>();
    entries.sort_by_key(|entry| entry.file_name().to_string_lossy().to_lowercase());

    for entry in entries {
        if tree.len() >= CONTEXT_TREE_ENTRY_LIMIT {
            *truncated = true;
            break;
        }
        let name = entry.file_name();
        let name_text = name.to_string_lossy();
        if name_text.starts_with('.') || excluded(&name) {
            continue;
        }
        let metadata = fs::symlink_metadata(entry.path()).map_err(|error| error.to_string())?;
        if metadata.file_type().is_symlink() {
            continue;
        }
        let relative = entry
            .path()
            .strip_prefix(root)
            .map_err(|_| "项目目录越过工作区边界".to_string())?
            .to_path_buf();
        let relative_text = relative.to_string_lossy().to_string();
        if metadata.is_dir() {
            tree.push(format!("{relative_text}/"));
            collect_workspace_context(root, &entry.path(), depth + 1, tree, candidates, truncated)?;
        } else if metadata.is_file() {
            tree.push(relative_text);
            if let Some(priority) = context_file_priority(&relative) {
                candidates.push((priority, entry.path()));
            }
        }
    }
    Ok(())
}

fn context_file_priority(relative: &Path) -> Option<u8> {
    let file_name = relative.file_name()?.to_string_lossy().to_lowercase();
    let normalized = relative.to_string_lossy().replace('\\', "/").to_lowercase();
    if file_name.starts_with("readme") {
        return Some(if relative.components().count() == 1 {
            0
        } else {
            1
        });
    }
    if matches!(
        file_name.as_ref(),
        "pom.xml"
            | "package.json"
            | "pyproject.toml"
            | "cargo.toml"
            | "go.mod"
            | "build.gradle"
            | "build.gradle.kts"
            | "settings.gradle"
            | "settings.gradle.kts"
            | "composer.json"
    ) {
        return Some(2);
    }
    if normalized.starts_with("docs/")
        && file_name.ends_with(".md")
        && (file_name.contains("overview")
            || file_name.contains("architecture")
            || file_name.contains("summary"))
    {
        return Some(3);
    }
    if file_name == "main.rs"
        || file_name == "main.py"
        || file_name == "app.py"
        || file_name == "main.ts"
        || file_name == "main.js"
        || file_name.ends_with("application.java")
    {
        return Some(4);
    }
    None
}

#[tauri::command]
pub fn git_status(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
) -> Result<GitStatus, String> {
    state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    ensure_repository_root(&workspace.root)?;
    let branch = git_text(
        &workspace.root,
        &["symbolic-ref", "--quiet", "--short", "HEAD"],
    )
    .unwrap_or_else(|_| "HEAD".to_string());
    let upstream = git_text(
        &workspace.root,
        &[
            "rev-parse",
            "--abbrev-ref",
            "--symbolic-full-name",
            "@{upstream}",
        ],
    )
    .ok();
    let (ahead, behind) = if upstream.is_some() {
        git_text(
            &workspace.root,
            &["rev-list", "--left-right", "--count", "HEAD...@{upstream}"],
        )
        .ok()
        .and_then(|value| {
            let mut parts = value.split_whitespace();
            Some((parts.next()?.parse().ok()?, parts.next()?.parse().ok()?))
        })
        .unwrap_or((0, 0))
    } else {
        (0, 0)
    };
    let output = git_bytes(
        &workspace.root,
        &["status", "--porcelain=v1", "-z", "--untracked-files=all"],
        DIFF_BYTE_LIMIT,
    )?;
    Ok(GitStatus {
        repository: workspace.root.to_string_lossy().to_string(),
        branch,
        upstream,
        ahead,
        behind,
        entries: parse_status(&output),
    })
}

#[tauri::command]
pub fn git_diff(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
    scope: String,
    path: Option<String>,
) -> Result<GitDiff, String> {
    state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    ensure_repository_root(&workspace.root)?;
    let scope = match scope.as_str() {
        "working" | "staged" => scope,
        _ => return Err("Diff scope 只能是 working 或 staged".to_string()),
    };
    let safe_path = path
        .as_deref()
        .map(|value| validate_relative(value).map(|_| value.to_string()))
        .transpose()?;
    let mut args = vec!["diff", "--no-ext-diff", "--no-color", "--unified=3"];
    if scope == "staged" {
        args.push("--cached");
    }
    if let Some(ref selected) = safe_path {
        args.push("--");
        args.push(selected);
    }
    let mut patch = git_bytes(&workspace.root, &args, DIFF_BYTE_LIMIT + 1)
        .map(|bytes| String::from_utf8_lossy(&bytes).to_string())?;
    if scope == "working" {
        append_untracked_diff(&workspace.root, safe_path.as_deref(), &mut patch)?;
    }
    let (patch, additions, deletions, truncated) = truncate_diff(patch);
    Ok(GitDiff {
        scope,
        path: safe_path,
        patch,
        additions,
        deletions,
        truncated,
    })
}

#[tauri::command]
pub fn terminal_create(
    app: AppHandle,
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
    cols: u16,
    rows: u16,
) -> Result<TerminalSession, String> {
    let grant = state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/zsh".to_string());
    let pair = native_pty_system()
        .openpty(PtySize {
            rows: rows.clamp(2, 300),
            cols: cols.clamp(10, 500),
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|error| format!("无法创建终端：{error}"))?;
    let mut command = CommandBuilder::new(&shell);
    command.cwd(&workspace.root);
    command.env("TERM", "xterm-256color");
    let child = pair
        .slave
        .spawn_command(command)
        .map_err(|error| format!("无法启动 Shell：{error}"))?;
    drop(pair.slave);
    let mut reader = pair
        .master
        .try_clone_reader()
        .map_err(|error| format!("无法读取终端：{error}"))?;
    let writer = pair
        .master
        .take_writer()
        .map_err(|error| format!("无法写入终端：{error}"))?;
    let session_id = Uuid::new_v4().to_string();
    let reader_session = session_id.clone();
    let window_label = grant.window_label.clone();
    let output_app = app.clone();
    std::thread::spawn(move || {
        let mut buffer = [0u8; 8192];
        loop {
            match reader.read(&mut buffer) {
                Ok(0) => break,
                Ok(count) => {
                    let payload = TerminalOutputEvent {
                        session_id: reader_session.clone(),
                        data_base64: base64::engine::general_purpose::STANDARD
                            .encode(&buffer[..count]),
                    };
                    let _ = output_app.emit_to(&window_label, "terminal-output", payload);
                }
                Err(_) => break,
            }
        }
    });
    let child = Arc::new(Mutex::new(child));
    let monitor_child = Arc::clone(&child);
    let monitor_session = session_id.clone();
    let monitor_window = window.label().to_string();
    let monitor_app = app.clone();
    std::thread::spawn(move || loop {
        let status = monitor_child
            .lock()
            .ok()
            .and_then(|mut child| child.try_wait().ok().flatten());
        if let Some(status) = status {
            if let Ok(mut inner) = monitor_app.state::<WorkspaceState>().inner.lock() {
                inner.terminals.remove(&monitor_session);
            }
            let _ = monitor_app.emit_to(
                &monitor_window,
                "terminal-exit",
                TerminalExitEvent {
                    session_id: monitor_session,
                    exit_code: Some(status.exit_code()),
                },
            );
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    });
    state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?
        .terminals
        .insert(
            session_id.clone(),
            TerminalNative {
                grant_id,
                window_label: window.label().to_string(),
                workspace_id: workspace_id.clone(),
                master: pair.master,
                writer,
                child,
            },
        );
    Ok(TerminalSession {
        id: session_id,
        workspace_id,
        shell: shell.clone(),
        cwd: workspace.root.to_string_lossy().to_string(),
    })
}

#[tauri::command]
pub fn terminal_write(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    session_id: String,
    data: String,
) -> Result<(), String> {
    state.require_grant(&grant_id, window.label())?;
    let mut inner = state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?;
    let terminal = inner
        .terminals
        .get_mut(&session_id)
        .ok_or_else(|| "终端会话不存在".to_string())?;
    if terminal.window_label != window.label() || terminal.grant_id != grant_id {
        return Err("终端会话不属于当前授权".to_string());
    }
    terminal
        .writer
        .write_all(data.as_bytes())
        .and_then(|_| terminal.writer.flush())
        .map_err(|error| format!("无法写入终端：{error}"))
}

#[tauri::command]
pub fn terminal_resize(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    session_id: String,
    cols: u16,
    rows: u16,
) -> Result<(), String> {
    state.require_grant(&grant_id, window.label())?;
    let inner = state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?;
    let terminal = inner
        .terminals
        .get(&session_id)
        .ok_or_else(|| "终端会话不存在".to_string())?;
    terminal
        .master
        .resize(PtySize {
            rows: rows.clamp(2, 300),
            cols: cols.clamp(10, 500),
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|error| format!("无法调整终端尺寸：{error}"))
}

#[tauri::command]
pub fn terminal_close(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    session_id: String,
) -> Result<(), String> {
    state.require_grant(&grant_id, window.label())?;
    let mut inner = state
        .inner
        .lock()
        .map_err(|_| "工作区状态不可用".to_string())?;
    let mut terminal = inner
        .terminals
        .remove(&session_id)
        .ok_or_else(|| "终端会话不存在".to_string())?;
    if terminal.window_label != window.label() || terminal.grant_id != grant_id {
        inner.terminals.insert(session_id, terminal);
        return Err("终端会话不属于当前授权".to_string());
    }
    WorkspaceState::close_terminal_locked(&mut terminal);
    Ok(())
}

pub fn start_grant_reaper(app: AppHandle) {
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(10));
        app.state::<WorkspaceState>().reap_expired();
    });
}

fn workspace_summary(entry: &WorkspaceEntry) -> WorkspaceSummary {
    WorkspaceSummary {
        id: entry.id.clone(),
        name: entry
            .root
            .file_name()
            .and_then(OsStr::to_str)
            .unwrap_or("Workspace")
            .to_string(),
        root: entry.root.to_string_lossy().to_string(),
        git_repository: ensure_repository_root(&entry.root).is_ok(),
        last_opened_at: entry.last_opened_at,
    }
}

fn resolve_existing(root: &Path, relative: &str) -> Result<PathBuf, String> {
    let relative = validate_relative(relative)?;
    let path = root.join(relative);
    let canonical = path
        .canonicalize()
        .map_err(|error| format!("路径不存在或不可访问：{error}"))?;
    if !canonical.starts_with(root) {
        return Err("路径越过工作区边界".to_string());
    }
    Ok(canonical)
}

fn validate_relative(value: &str) -> Result<PathBuf, String> {
    let path = PathBuf::from(value.trim());
    if path.is_absolute()
        || path.components().any(|component| {
            matches!(
                component,
                Component::ParentDir | Component::RootDir | Component::Prefix(_)
            )
        })
    {
        return Err("路径必须位于工作区内".to_string());
    }
    Ok(path)
}

fn unique_attachment_path(root: &Path, file_name: &str) -> PathBuf {
    let requested = root.join(file_name);
    if !requested.exists() {
        return requested;
    }
    let path = Path::new(file_name);
    let stem = path
        .file_stem()
        .and_then(OsStr::to_str)
        .unwrap_or("attachment");
    let extension = path.extension().and_then(OsStr::to_str);
    for suffix in 1..10_000 {
        let candidate = match extension {
            Some(value) => root.join(format!("{stem} ({suffix}).{value}")),
            None => root.join(format!("{stem} ({suffix})")),
        };
        if !candidate.exists() {
            return candidate;
        }
    }
    root.join(format!("{}-{}", Uuid::new_v4(), file_name))
}

fn file_entry(root: &Path, entry: fs::DirEntry) -> Result<FileEntry, String> {
    let path = entry.path();
    let metadata = fs::symlink_metadata(&path).map_err(|error| error.to_string())?;
    let is_symlink = metadata.file_type().is_symlink();
    let canonical = path.canonicalize().ok();
    let inside = canonical
        .as_ref()
        .is_some_and(|value| value.starts_with(root));
    let followed = fs::metadata(&path).ok();
    let kind = if is_symlink && !inside {
        "symlink"
    } else if followed.as_ref().is_some_and(|value| value.is_dir()) {
        "directory"
    } else {
        "file"
    };
    Ok(FileEntry {
        name: entry.file_name().to_string_lossy().to_string(),
        relative_path: root
            .relativize(&path)
            .unwrap_or_else(|| path.strip_prefix(root).unwrap_or(&path).to_path_buf())
            .to_string_lossy()
            .to_string(),
        kind: kind.to_string(),
        size: metadata.len(),
        modified_at: metadata.modified().ok().map(system_time_millis),
        traversable: !is_symlink || inside,
    })
}

trait Relativize {
    fn relativize(&self, path: &Path) -> Option<PathBuf>;
}

impl Relativize for Path {
    fn relativize(&self, path: &Path) -> Option<PathBuf> {
        path.strip_prefix(self).ok().map(Path::to_path_buf)
    }
}

fn excluded(name: &OsStr) -> bool {
    matches!(
        name.to_string_lossy().as_ref(),
        ".git" | "node_modules" | "target" | "dist" | ".idea" | ".gradle"
    )
}

fn language_for(path: &Path) -> String {
    match path.extension().and_then(OsStr::to_str).unwrap_or_default() {
        "rs" => "rust",
        "java" => "java",
        "js" | "mjs" | "cjs" => "javascript",
        "ts" => "typescript",
        "vue" => "vue",
        "json" => "json",
        "md" => "markdown",
        "yml" | "yaml" => "yaml",
        "toml" => "toml",
        "xml" => "xml",
        "css" => "css",
        "html" => "html",
        "sh" | "zsh" => "shell",
        _ => "text",
    }
    .to_string()
}

fn ensure_repository_root(root: &Path) -> Result<(), String> {
    let actual = git_text(root, &["rev-parse", "--show-toplevel"])?;
    let actual = PathBuf::from(actual)
        .canonicalize()
        .map_err(|error| format!("无法解析 Git 仓库：{error}"))?;
    if actual != root {
        return Err("请选择 Git 仓库根目录以查看 Diff".to_string());
    }
    Ok(())
}

fn git_text(root: &Path, args: &[&str]) -> Result<String, String> {
    let output = git_bytes(root, args, DIFF_BYTE_LIMIT)?;
    Ok(String::from_utf8_lossy(&output).trim().to_string())
}

fn git_bytes(root: &Path, args: &[&str], limit: usize) -> Result<Vec<u8>, String> {
    let mut child = Command::new("git")
        .arg("-C")
        .arg(root)
        .args(args)
        .env("GIT_TERMINAL_PROMPT", "0")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| format!("无法执行 Git：{error}"))?;
    let mut bytes = Vec::new();
    child
        .stdout
        .take()
        .ok_or_else(|| "无法读取 Git 输出".to_string())?
        .take(limit as u64)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("无法读取 Git 输出：{error}"))?;
    if bytes.len() >= limit {
        let _ = child.kill();
    }
    let output = child
        .wait_with_output()
        .map_err(|error| format!("无法等待 Git 退出：{error}"))?;
    if !output.status.success() && bytes.len() < limit {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    Ok(bytes)
}

fn parse_status(output: &[u8]) -> Vec<GitStatusEntry> {
    let records = output.split(|byte| *byte == 0).collect::<Vec<_>>();
    let mut entries = Vec::new();
    let mut index = 0;
    while index < records.len() {
        let record = records[index];
        if record.len() < 4 {
            index += 1;
            continue;
        }
        let index_status = (record[0] as char).to_string();
        let worktree_status = (record[1] as char).to_string();
        let path = String::from_utf8_lossy(&record[3..]).to_string();
        let renamed = matches!(record[0], b'R' | b'C') || matches!(record[1], b'R' | b'C');
        let old_path = if renamed && index + 1 < records.len() {
            index += 1;
            Some(String::from_utf8_lossy(records[index]).to_string())
        } else {
            None
        };
        entries.push(GitStatusEntry {
            path,
            old_path,
            index_status,
            worktree_status,
        });
        index += 1;
    }
    entries
}

fn append_untracked_diff(
    root: &Path,
    selected: Option<&str>,
    patch: &mut String,
) -> Result<(), String> {
    let status = git_bytes(
        root,
        &["ls-files", "--others", "--exclude-standard", "-z"],
        DIFF_BYTE_LIMIT,
    )?;
    for raw in status
        .split(|byte| *byte == 0)
        .filter(|value| !value.is_empty())
    {
        let relative = String::from_utf8_lossy(raw).to_string();
        if selected.is_some_and(|value| value != relative) {
            continue;
        }
        let path = resolve_existing(root, &relative)?;
        if !path.is_file() || path.metadata().map(|value| value.len()).unwrap_or(0) > FILE_LIMIT {
            continue;
        }
        let output = Command::new("git")
            .arg("-C")
            .arg(root)
            .args([
                "diff",
                "--no-index",
                "--no-color",
                "--unified=3",
                "--",
                "/dev/null",
            ])
            .arg(&relative)
            .output()
            .map_err(|error| format!("无法读取未跟踪文件 Diff：{error}"))?;
        if output.status.code() == Some(1) {
            patch.push_str(&String::from_utf8_lossy(&output.stdout));
        }
        if patch.len() > DIFF_BYTE_LIMIT {
            break;
        }
    }
    Ok(())
}

fn truncate_diff(value: String) -> (String, usize, usize, bool) {
    let mut additions = 0;
    let mut deletions = 0;
    let mut kept = String::new();
    let mut truncated = value.len() > DIFF_BYTE_LIMIT;
    for (index, line) in value.lines().enumerate() {
        if index >= DIFF_LINE_LIMIT || kept.len() + line.len() + 1 > DIFF_BYTE_LIMIT {
            truncated = true;
            break;
        }
        if line.starts_with('+') && !line.starts_with("+++") {
            additions += 1;
        } else if line.starts_with('-') && !line.starts_with("---") {
            deletions += 1;
        }
        kept.push_str(line);
        kept.push('\n');
    }
    (kept, additions, deletions, truncated)
}

fn now_millis() -> u64 {
    system_time_millis(SystemTime::now())
}

fn system_time_millis(value: SystemTime) -> u64 {
    value
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temporary_directory(name: &str) -> PathBuf {
        let path = std::env::temp_dir().join(format!("agentos-{name}-{}", Uuid::new_v4()));
        fs::create_dir_all(&path).unwrap();
        path
    }

    #[test]
    fn rejects_parent_and_absolute_paths() {
        assert!(validate_relative("src/main.rs").is_ok());
        assert!(validate_relative("../secret").is_err());
        assert!(validate_relative("/etc/passwd").is_err());
    }

    #[test]
    fn workspace_access_accepts_workspace_or_admin_role() {
        assert!(can_access_workspace(&["WORKSPACE".to_string()]));
        assert!(can_access_workspace(&["admin".to_string()]));
        assert!(!can_access_workspace(&["USER".to_string()]));
    }

    #[test]
    fn parses_porcelain_status() {
        let parsed = parse_status(b" M src/main.rs\0?? notes.txt\0R  new.txt\0old.txt\0");
        assert_eq!(parsed.len(), 3);
        assert_eq!(parsed[0].worktree_status, "M");
        assert_eq!(parsed[1].index_status, "?");
        assert_eq!(parsed[2].old_path.as_deref(), Some("old.txt"));
    }

    #[test]
    fn diff_limits_and_counts_changes() {
        let input = "--- a/file\n+++ b/file\n-old\n+new\n context\n".to_string();
        let (_, additions, deletions, truncated) = truncate_diff(input);
        assert_eq!((additions, deletions, truncated), (1, 1, false));
    }

    #[test]
    fn grant_is_window_bound_and_expires() {
        let root = temporary_directory("grant");
        let state = WorkspaceState::new(root.join("workspaces.json"));
        state.inner.lock().unwrap().grants.insert(
            "valid".to_string(),
            Grant {
                window_label: "main".to_string(),
                user: "alice".to_string(),
                expires_at: Instant::now() + Duration::from_secs(1),
            },
        );
        state.inner.lock().unwrap().grants.insert(
            "expired".to_string(),
            Grant {
                window_label: "main".to_string(),
                user: "alice".to_string(),
                expires_at: Instant::now() - Duration::from_secs(1),
            },
        );
        assert!(state.require_grant("valid", "main").is_ok());
        assert!(state.require_grant("valid", "other").is_err());
        assert!(state.require_grant("expired", "main").is_err());
        fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlink_that_leaves_workspace() {
        use std::os::unix::fs::symlink;
        let root = temporary_directory("root");
        let outside = temporary_directory("outside");
        fs::write(outside.join("secret.txt"), "secret").unwrap();
        symlink(outside.join("secret.txt"), root.join("escape.txt")).unwrap();
        assert!(resolve_existing(&root, "escape.txt").is_err());
        fs::remove_dir_all(root).unwrap();
        fs::remove_dir_all(outside).unwrap();
    }

    #[test]
    fn git_helpers_cover_staged_unstaged_untracked_and_binary() {
        let root = temporary_directory("git");
        let run = |args: &[&str]| {
            let output = Command::new("git")
                .current_dir(&root)
                .args(args)
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "git {:?}: {}",
                args,
                String::from_utf8_lossy(&output.stderr)
            );
        };
        run(&["init", "-q"]);
        run(&["config", "user.email", "agentos@example.invalid"]);
        run(&["config", "user.name", "AgentOS Test"]);
        fs::write(root.join("tracked.txt"), "one\n").unwrap();
        run(&["add", "tracked.txt"]);
        run(&["commit", "-qm", "initial"]);
        fs::write(root.join("tracked.txt"), "one\ntwo\n").unwrap();
        fs::write(root.join("staged.txt"), "staged\n").unwrap();
        fs::write(root.join("untracked.txt"), "new\n").unwrap();
        fs::write(root.join("binary.bin"), [0_u8, 1, 2, 3]).unwrap();
        run(&["add", "staged.txt", "binary.bin"]);
        let output = git_bytes(
            &root,
            &["status", "--porcelain=v1", "-z", "--untracked-files=all"],
            DIFF_BYTE_LIMIT,
        )
        .unwrap();
        let entries = parse_status(&output);
        assert!(entries
            .iter()
            .any(|entry| entry.path == "tracked.txt" && entry.worktree_status == "M"));
        assert!(entries
            .iter()
            .any(|entry| entry.path == "staged.txt" && entry.index_status == "A"));
        assert!(entries
            .iter()
            .any(|entry| entry.path == "untracked.txt" && entry.index_status == "?"));
        let staged =
            git_bytes(&root, &["diff", "--cached", "--no-color"], DIFF_BYTE_LIMIT).unwrap();
        assert!(String::from_utf8_lossy(&staged).contains("Binary files"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn truncates_diff_by_line_count() {
        let input = (0..=DIFF_LINE_LIMIT)
            .map(|index| format!("+line-{index}\n"))
            .collect::<String>();
        let (patch, _, _, truncated) = truncate_diff(input);
        assert!(truncated);
        assert_eq!(patch.lines().count(), DIFF_LINE_LIMIT);
    }

    #[test]
    fn pty_supports_output_resize_and_close() {
        let pair = native_pty_system()
            .openpty(PtySize {
                rows: 10,
                cols: 40,
                pixel_width: 0,
                pixel_height: 0,
            })
            .unwrap();
        let mut command = CommandBuilder::new("/bin/sh");
        command.args(["-c", "printf agentos-ready"]);
        let mut child = pair.slave.spawn_command(command).unwrap();
        drop(pair.slave);
        pair.master
            .resize(PtySize {
                rows: 20,
                cols: 80,
                pixel_width: 0,
                pixel_height: 0,
            })
            .unwrap();
        let mut reader = pair.master.try_clone_reader().unwrap();
        let mut output = Vec::new();
        reader.read_to_end(&mut output).unwrap();
        let _ = child.wait();
        assert!(String::from_utf8_lossy(&output).contains("agentos-ready"));
    }

    #[test]
    fn file_preview_detects_binary_and_enforces_limit() {
        let root = temporary_directory("file-preview");
        let binary = root.join("image.bin");
        fs::write(&binary, [1_u8, 0, 2, 3]).unwrap();
        let binary_preview = read_file_content(&binary, "image.bin".to_string()).unwrap();
        assert!(binary_preview.binary);
        assert!(binary_preview.content.is_none());

        let large = root.join("large.txt");
        fs::write(&large, vec![b'x'; FILE_LIMIT as usize + 64]).unwrap();
        let large_preview = read_file_content(&large, "large.txt".to_string()).unwrap();
        assert!(large_preview.truncated);
        assert_eq!(large_preview.content.unwrap().len(), FILE_LIMIT as usize);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn workspace_context_includes_project_evidence_and_excludes_secrets() {
        let root = temporary_directory("project-context");
        fs::create_dir_all(root.join("src/main/java")).unwrap();
        fs::create_dir_all(root.join("target/classes")).unwrap();
        fs::write(root.join("ReadMe.md"), "# Demo\nA local agent project.").unwrap();
        fs::write(root.join("pom.xml"), "<artifactId>demo-agent</artifactId>").unwrap();
        fs::write(root.join(".env"), "API_KEY=secret").unwrap();
        fs::write(root.join("target/classes/secret.txt"), "secret").unwrap();
        fs::write(
            root.join("src/main/java/DemoApplication.java"),
            "class DemoApplication {}",
        )
        .unwrap();
        let workspace = WorkspaceEntry {
            id: "workspace-context".to_string(),
            root: root.canonicalize().unwrap(),
            last_opened_at: 0,
        };

        let context = build_workspace_context(
            &workspace,
            &["src/main/java/DemoApplication.java".to_string()],
        )
        .unwrap();

        let paths = context
            .files
            .iter()
            .map(|file| file.path.as_str())
            .collect::<Vec<_>>();
        assert_eq!(context.name, root.file_name().unwrap().to_string_lossy());
        assert!(paths.contains(&"ReadMe.md"));
        assert!(paths.contains(&"pom.xml"));
        assert!(paths.contains(&"src/main/java/DemoApplication.java"));
        assert!(context
            .files
            .iter()
            .any(|file| { file.path == "src/main/java/DemoApplication.java" && file.mentioned }));
        assert!(context.tree.iter().all(|path| !path.contains(".env")));
        assert!(context.tree.iter().all(|path| !path.starts_with("target")));
        assert!(context
            .files
            .iter()
            .all(|file| !file.content.contains("API_KEY")));
        let index = build_workspace_file_index(&root).unwrap();
        let indexed_paths = index
            .iter()
            .map(|file| file.relative_path.as_str())
            .collect::<Vec<_>>();
        assert!(indexed_paths.contains(&"ReadMe.md"));
        assert!(indexed_paths.contains(&"src/main/java/DemoApplication.java"));
        assert!(!indexed_paths.iter().any(|path| path.contains(".env")));
        assert!(!indexed_paths.iter().any(|path| path.starts_with("target")));
        assert!(build_workspace_context(&workspace, &[".env".to_string()]).is_err());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn persisted_workspace_keeps_opaque_id() {
        let root = temporary_directory("persist-root");
        let config = temporary_directory("persist-config");
        let storage = config.join("workspaces.json");
        let state = WorkspaceState::new(storage.clone());
        let entry = WorkspaceEntry {
            id: "opaque-workspace-id".to_string(),
            root: root.clone(),
            last_opened_at: 42,
        };
        {
            let mut inner = state.inner.lock().unwrap();
            inner.workspaces.insert(entry.id.clone(), entry);
            state.persist(&inner).unwrap();
        }
        let restored = WorkspaceState::new(storage);
        assert!(restored
            .inner
            .lock()
            .unwrap()
            .workspaces
            .contains_key("opaque-workspace-id"));
        fs::remove_dir_all(root).unwrap();
        fs::remove_dir_all(config).unwrap();
    }
}
