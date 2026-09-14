//! Local execution for server tool calls; grants and canonical roots come from the native picker.
use super::*;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

#[derive(Default)]
pub struct ExecutionState {
    operations: Mutex<HashMap<String, Arc<OperationControl>>>,
    locks: Mutex<HashMap<String, String>>,
}

struct OperationControl {
    cancelled: AtomicBool,
    heartbeat: AtomicU64,
}
impl ExecutionState {
    pub fn cleanup_window(&self, label: &str) {
        let prefix = format!("{label}:");
        if let Ok(operations) = self.operations.lock() {
            for (key, control) in operations.iter() {
                if key.starts_with(&prefix) {
                    control.cancelled.store(true, Ordering::SeqCst);
                }
            }
        }
        if let Ok(mut locks) = self.locks.lock() {
            locks.retain(|key, _| !key.starts_with(&prefix));
        }
    }
}

#[tauri::command]
pub fn workspace_execution_heartbeat(
    window: WebviewWindow,
    execution: State<'_, ExecutionState>,
    operation_ids: Vec<String>,
) {
    if let Ok(operations) = execution.operations.lock() {
        for id in operation_ids {
            if let Some(control) = operations.get(&format!("{}:{id}", window.label())) {
                control.heartbeat.store(now_millis(), Ordering::SeqCst);
            }
        }
    }
}

pub fn branch(root: &Path) -> String {
    git_text(root, &["symbolic-ref", "--short", "HEAD"])
        .or_else(|_| git_text(root, &["rev-parse", "HEAD"]))
        .unwrap_or_default()
        .trim()
        .to_string()
}

#[tauri::command]
pub fn workspace_execution_info(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    grant_id: String,
    workspace_id: String,
) -> Result<Value, String> {
    state.require_grant(&grant_id, window.label())?;
    let workspace = state.workspace(&workspace_id)?;
    if !workspace.root.is_dir()
        || workspace.root.canonicalize().map_err(|e| e.to_string())? != workspace.root
    {
        return Err("工作区目录不存在或路径已改变".into());
    }
    let device = std::env::var("COMPUTERNAME")
        .or_else(|_| {
            Command::new("hostname")
                .output()
                .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
                .map_err(|_| std::env::VarError::NotPresent)
        })
        .unwrap_or_else(|_| "本机".into());
    Ok(
        json!({"workspaceId": workspace.id, "root": workspace.root, "branch": branch(&workspace.root),
        "device": device, "osName": std::env::consts::OS}),
    )
}

#[tauri::command]
pub fn workspace_execution_lock(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    execution: State<'_, ExecutionState>,
    grant_id: String,
    workspace_id: String,
    session_id: String,
    locked: bool,
) -> Result<(), String> {
    state.require_grant(&grant_id, window.label())?;
    state.workspace(&workspace_id)?;
    let key = format!("{}:{session_id}", window.label());
    let mut locks = execution.locks.lock().map_err(|e| e.to_string())?;
    if locked {
        locks.insert(key, workspace_id);
    } else {
        locks.remove(&key);
    }
    Ok(())
}

pub fn require_unlocked(execution: &ExecutionState, workspace_id: &str) -> Result<(), String> {
    if execution
        .locks
        .lock()
        .map_err(|e| e.to_string())?
        .values()
        .any(|id| id == workspace_id)
    {
        return Err("该目录有任务正在运行，不能切换分支".into());
    }
    Ok(())
}

#[tauri::command]
pub fn cancel_workspace_operation(
    window: WebviewWindow,
    execution: State<'_, ExecutionState>,
    operation_id: String,
) {
    if let Ok(operations) = execution.operations.lock() {
        if let Some(cancel) = operations.get(&format!("{}:{operation_id}", window.label())) {
            cancel.cancelled.store(true, Ordering::SeqCst);
        }
    }
}

#[tauri::command]
pub async fn execute_workspace_operation(
    window: WebviewWindow,
    state: State<'_, WorkspaceState>,
    execution: State<'_, ExecutionState>,
    grant_id: String,
    workspace_id: String,
    expected_branch: String,
    operation_id: String,
    tool_name: String,
    arguments: Value,
    deadline: u64,
) -> Result<Value, String> {
    state.require_grant(&grant_id, window.label())?;
    let root = state.workspace(&workspace_id)?.root;
    let key = format!("{}:{operation_id}", window.label());
    let control = Arc::new(OperationControl {
        cancelled: AtomicBool::new(false),
        heartbeat: AtomicU64::new(now_millis()),
    });
    let cancelled = control.clone();
    {
        let mut operations = execution.operations.lock().map_err(|e| e.to_string())?;
        if operations.contains_key(&key) {
            return Err("本机操作正在执行，不能重复执行".into());
        }
        operations.insert(key.clone(), cancelled.clone());
    }
    let watchdog = control.clone();
    std::thread::spawn(move || {
        while !watchdog.cancelled.load(Ordering::SeqCst) {
            if now_millis().saturating_sub(watchdog.heartbeat.load(Ordering::SeqCst)) > 12_000 {
                watchdog.cancelled.store(true, Ordering::SeqCst);
                break;
            }
            std::thread::sleep(Duration::from_millis(500));
        }
    });
    let result = tauri::async_runtime::spawn_blocking(move || {
        if now_millis() >= deadline || !root.is_dir() || root.canonicalize().ok().as_ref() != Some(&root)
            || branch(&root) != expected_branch {
            return json!({"success": false, "error": "目录、分支或操作有效期已改变，请恢复任务工作区后继续", "unavailable": true});
        }
        match execute(&root, &tool_name, &arguments, &cancelled.cancelled, deadline) {
            Ok(data) => {
                let failed = matches!(tool_name.as_str(), "run_command" | "git_commit") && data["exitCode"] != 0;
                json!({"success": !failed, "data": data, "unavailable": false,
                    "error": if failed { "本机命令未成功完成，请检查 exitCode、stdout 和 stderr" } else { "" }})
            },
            Err(error) => json!({"success": false, "error": error, "unavailable": false}),
        }
    }).await.map_err(|e| e.to_string());
    control.cancelled.store(true, Ordering::SeqCst);
    execution
        .operations
        .lock()
        .map_err(|e| e.to_string())?
        .remove(&key);
    result
}

fn text<'a>(args: &'a Value, key: &str) -> Result<&'a str, String> {
    args.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("缺少字符串参数 {key}"))
}
fn number(args: &Value, key: &str, default: u64, max: u64) -> u64 {
    args.get(key)
        .and_then(Value::as_u64)
        .unwrap_or(default)
        .min(max)
}
fn path(root: &Path, raw: &str, writing: bool) -> Result<PathBuf, String> {
    let requested = Path::new(raw);
    let relative = if requested.is_absolute() {
        requested
            .strip_prefix(root)
            .map_err(|_| "路径不在任务工作区内")?
    } else {
        requested
    };
    if relative.components().any(|c| {
        matches!(
            c,
            Component::ParentDir | Component::RootDir | Component::Prefix(_)
        )
    }) {
        return Err("路径不能越过任务工作区".into());
    }
    if relative.components().any(|c| c.as_os_str() == ".git") {
        return Err("请通过 Git 工具操作 .git".into());
    }
    let candidate = root.join(relative);
    let mut ancestor = candidate.as_path();
    while !ancestor.exists() {
        ancestor = ancestor.parent().ok_or("路径不可用")?;
    }
    if !ancestor
        .canonicalize()
        .map_err(|e| e.to_string())?
        .starts_with(root)
    {
        return Err("符号链接指向工作区之外".into());
    }
    if !writing && !candidate.exists() {
        return Err("文件或目录不存在".into());
    }
    // A dangling symlink must not turn a write into an escape.
    if fs::symlink_metadata(&candidate).is_ok() && !candidate.exists() {
        return Err("目标符号链接失效".into());
    }
    Ok(candidate)
}

fn execute(
    root: &Path,
    tool: &str,
    args: &Value,
    cancel: &AtomicBool,
    deadline: u64,
) -> Result<Value, String> {
    match tool {
        "run_command" => command(
            root,
            text(args, "command")?,
            number(args, "timeout_seconds", 60, 120).max(1),
            cancel,
            deadline,
        ),
        "file_read" => {
            let file = path(root, text(args, "path")?, false)?;
            let bytes = read_bounded(&file)?;
            let content = std::str::from_utf8(&bytes)
                .map_err(|_| "本机文件读取支持 UTF-8 文本；二进制文档请使用本机命令解析")?;
            let offset = number(args, "offset", 0, u64::MAX) as usize;
            if offset > content.chars().count() {
                return Err("offset 超过文件字符数".into());
            }
            let page: String = content.chars().skip(offset).take(20_000).collect();
            let next = offset + page.chars().count();
            Ok(
                json!({"path": file, "content": page, "offset": offset, "nextOffset": next,
                "hasMore": content.chars().count() > next, "totalChars": content.chars().count(),
                "truncated": content.chars().count() > next}),
            )
        }
        "file_write" => {
            let file = path(root, text(args, "path")?, true)?;
            if matches!(
                file.extension().and_then(OsStr::to_str),
                Some("docx" | "pdf" | "xlsx" | "png" | "jpg" | "zip")
            ) {
                return Err(
                    "本机 file_write 仅支持文本和源码，二进制文档请使用本机命令生成".into(),
                );
            }
            let content = text(args, "content")?;
            if content.len() > FILE_LIMIT as usize {
                return Err("写入内容超过 1 MiB".into());
            }
            let mode = args
                .get("mode")
                .and_then(Value::as_str)
                .unwrap_or("CREATE_NEW");
            if !["CREATE_NEW", "OVERWRITE", "APPEND"].contains(&mode) {
                return Err("无效写入模式".into());
            }
            if args
                .get("createParentDirectories")
                .and_then(Value::as_bool)
                .unwrap_or(false)
            {
                if let Some(parent) = file.parent() {
                    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
                }
            }
            path(root, text(args, "path")?, true)?;
            let mut options = fs::OpenOptions::new();
            options.write(true);
            match mode {
                "OVERWRITE" => {
                    options.create(true).truncate(true);
                }
                "APPEND" => {
                    options.create(true).append(true);
                }
                _ => {
                    options.create_new(true);
                }
            }
            options
                .open(&file)
                .and_then(|mut f| f.write_all(content.as_bytes()))
                .map_err(|e| e.to_string())?;
            Ok(json!({"path": file, "bytesWritten": content.len(), "mode": mode}))
        }
        "directory_list" | "file_search" => {
            let base = path(root, text(args, "path")?, false)?;
            if !base.is_dir() {
                return Err("搜索或列出路径必须是目录".into());
            }
            let search = tool == "file_search";
            let max_depth = number(args, "maxDepth", if search { 8 } else { 2 }, 12) as usize;
            let limit = number(
                args,
                if search { "maxResults" } else { "maxEntries" },
                200,
                500,
            )
            .max(1) as usize;
            let mut entries = Vec::new();
            walk(
                root,
                &base,
                0,
                max_depth,
                limit,
                args,
                search,
                &mut entries,
                cancel,
                deadline,
            )?;
            Ok(json!({"path": base, "truncated": entries.len() >= limit, "entries": entries}))
        }
        "git_commit" => git_commit(root, args, cancel, deadline),
        _ => Err(format!("本机不支持工具 {tool}")),
    }
}
fn read_bounded(file: &Path) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    File::open(file)
        .map_err(|e| e.to_string())?
        .take(FILE_LIMIT + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() > FILE_LIMIT as usize {
        return Err("文件超过 1 MiB，请通过命令分段读取".into());
    }
    Ok(bytes)
}
fn glob(pattern: &str, value: &str) -> bool {
    // Glob name matching without running a shell. '*' also matches path separators for **/ patterns.
    let (p, v) = (pattern.as_bytes(), value.as_bytes());
    let (mut i, mut j, mut star, mut start) = (0, 0, None, 0);
    while j < v.len() {
        if i < p.len() && (p[i] == b'?' || p[i] == v[j]) {
            i += 1;
            j += 1;
        } else if i < p.len() && p[i] == b'*' {
            star = Some(i);
            i += 1;
            start = j;
        } else if let Some(s) = star {
            start += 1;
            j = start;
            i = s + 1;
        } else {
            return false;
        }
    }
    while i < p.len() && p[i] == b'*' {
        i += 1;
    }
    i == p.len()
}
#[allow(clippy::too_many_arguments)]
fn walk(
    root: &Path,
    dir: &Path,
    depth: usize,
    max_depth: usize,
    limit: usize,
    args: &Value,
    search: bool,
    results: &mut Vec<Value>,
    cancel: &AtomicBool,
    deadline: u64,
) -> Result<(), String> {
    let mut entries = fs::read_dir(dir)
        .map_err(|e| e.to_string())?
        .filter_map(Result::ok)
        .take(10_000)
        .collect::<Vec<_>>();
    entries.sort_by_key(|e| e.file_name());
    for entry in entries {
        if cancel.load(Ordering::SeqCst) || now_millis() > deadline {
            return Err("本机操作已取消或超时".into());
        }
        if results.len() >= limit {
            break;
        }
        if entry.file_name() == ".git" {
            continue;
        }
        let file = entry.path();
        if fs::symlink_metadata(&file)
            .map(|m| m.file_type().is_symlink())
            .unwrap_or(true)
        {
            continue;
        }
        let relative = file
            .strip_prefix(root)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .to_string();
        if !search {
            results.push(json!({"path": relative, "directory": file.is_dir()}));
        } else {
            let query = text(args, "query")?;
            let case = args
                .get("caseSensitive")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            let normalized = if case {
                query.to_string()
            } else {
                query.to_lowercase()
            };
            let name = if case {
                relative.clone()
            } else {
                relative.to_lowercase()
            };
            match text(args, "mode")? {
                "NAME" => {
                    if glob(&normalized, &name)
                        || glob(&normalized, name.rsplit('/').next().unwrap_or(&name))
                        || normalized
                            .strip_prefix("**/")
                            .is_some_and(|p| glob(p, &name))
                    {
                        results.push(json!({"path": relative}));
                    }
                }
                "CONTENT" if file.is_file() => {
                    if let Ok(bytes) = read_bounded(&file) {
                        if let Ok(content) = std::str::from_utf8(&bytes) {
                            for (line, value) in content.lines().enumerate() {
                                let haystack = if case {
                                    value.to_string()
                                } else {
                                    value.to_lowercase()
                                };
                                if haystack.contains(&normalized) {
                                    results.push(json!({"path": relative, "line": line + 1,
                                        "text": value.chars().take(500).collect::<String>()}));
                                    if results.len() >= limit {
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                "CONTENT" => {}
                _ => return Err("搜索模式必须为 NAME 或 CONTENT".into()),
            }
        }
        if file.is_dir() && depth < max_depth {
            walk(
                root,
                &file,
                depth + 1,
                max_depth,
                limit,
                args,
                search,
                results,
                cancel,
                deadline,
            )?;
        }
    }
    Ok(())
}
fn terminate(child: &mut std::process::Child) {
    #[cfg(unix)]
    {
        let _ = Command::new("/bin/kill")
            .args(["-KILL", "--", &format!("-{}", child.id())])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    #[cfg(windows)]
    {
        let _ = Command::new("taskkill")
            .args(["/PID", &child.id().to_string(), "/T", "/F"])
            .status();
    }
    let _ = child.kill();
    let _ = child.wait();
}
fn capture<R: Read + Send + 'static>(mut reader: R) -> std::thread::JoinHandle<Vec<u8>> {
    std::thread::spawn(move || {
        let mut output = Vec::new();
        let mut chunk = [0u8; 4096];
        while let Ok(count) = reader.read(&mut chunk) {
            if count == 0 {
                break;
            }
            let keep = count.min(20_000usize.saturating_sub(output.len()));
            output.extend_from_slice(&chunk[..keep]); // drain excess output to avoid pipe deadlocks
        }
        output
    })
}
fn run(
    root: &Path,
    program: &str,
    args: &[&str],
    timeout: u64,
    cancel: &AtomicBool,
    deadline: u64,
) -> Result<Value, String> {
    let mut builder = Command::new(program);
    if program == "git" {
        builder
            .env("GIT_LITERAL_PATHSPECS", "1")
            .env("GIT_TERMINAL_PROMPT", "0");
    }
    builder
        .args(args)
        .current_dir(root)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        builder.process_group(0);
    }
    let mut child = builder
        .spawn()
        .map_err(|e| format!("无法启动本机命令: {e}"))?;
    let out = capture(child.stdout.take().ok_or("stdout 不可用")?);
    let err = capture(child.stderr.take().ok_or("stderr 不可用")?);
    let started = Instant::now();
    let mut interrupted = false;
    let status = loop {
        if cancel.load(Ordering::SeqCst)
            || started.elapsed().as_secs() >= timeout
            || now_millis() >= deadline
        {
            interrupted = true;
            terminate(&mut child);
            break child.try_wait().map_err(|e| e.to_string())?;
        }
        if let Some(status) = child.try_wait().map_err(|e| e.to_string())? {
            break Some(status);
        }
        std::thread::sleep(Duration::from_millis(50));
    };
    terminate(&mut child); // also close inherited pipes held by background descendants
    let stdout = out.join().map_err(|_| "输出读取失败")?;
    let stderr = err.join().map_err(|_| "错误输出读取失败")?;
    Ok(
        json!({"cwd": root, "exitCode": status.and_then(|s| s.code()), "interrupted": interrupted,
        "stdout": String::from_utf8_lossy(&stdout), "stderr": String::from_utf8_lossy(&stderr),
        "truncated": stdout.len() >= 20_000 || stderr.len() >= 20_000}),
    )
}
fn command(
    root: &Path,
    command: &str,
    timeout: u64,
    cancel: &AtomicBool,
    deadline: u64,
) -> Result<Value, String> {
    #[cfg(windows)]
    {
        run(root, "cmd", &["/c", command], timeout, cancel, deadline)
    }
    #[cfg(not(windows))]
    {
        let shell = std::env::var("SHELL").unwrap_or_else(|_| {
            if cfg!(target_os = "macos") {
                "/bin/zsh".into()
            } else {
                "/bin/sh".into()
            }
        });
        // A GUI process lacks the interactive PATH. Load the user's login environment, then
        // restore the selected cwd after profile scripts, passing paths and code as arguments.
        run(
            root,
            &shell,
            &[
                "-lc",
                "cd -- \"$1\" && eval \"$2\"",
                "agentos",
                &root.to_string_lossy(),
                command,
            ],
            timeout,
            cancel,
            deadline,
        )
    }
}
fn git_commit(
    root: &Path,
    args: &Value,
    cancel: &AtomicBool,
    deadline: u64,
) -> Result<Value, String> {
    let repository = path(root, text(args, "repository")?, false)?;
    if repository.canonicalize().map_err(|e| e.to_string())? != root {
        return Err("Git 提交必须使用任务目录".into());
    }
    ensure_repository_root(root)?;
    let paths = args
        .get("paths")
        .and_then(Value::as_array)
        .ok_or("paths 必须是数组")?;
    if paths.is_empty() {
        return Err("必须指定提交文件".into());
    }
    let files = paths
        .iter()
        .map(|v| v.as_str().ok_or("无效提交路径".to_string()))
        .collect::<Result<Vec<_>, _>>()?;
    for file in &files {
        path(root, file, true)?;
    }
    let staged = git_text(root, &["diff", "--cached", "--name-only"])?;
    if !staged.trim().is_empty() {
        return Err("仓库已有暂存内容，拒绝提交".into());
    }
    let message = text(args, "message")?;
    if message.trim().is_empty() || message.len() > 500 {
        return Err("提交说明无效".into());
    }
    let mut add = vec!["add", "--"];
    add.extend(files.iter().copied());
    let added = run(root, "git", &add, 30, cancel, deadline)?;
    if added["exitCode"] != 0 {
        return Ok(added);
    }
    let mut commit = vec!["commit", "--only", "-m", message, "--"];
    commit.extend(files.iter().copied());
    let result = run(root, "git", &commit, 60, cancel, deadline)?;
    if result["exitCode"] != 0 {
        let mut reset = vec!["reset", "--"];
        reset.extend(files.iter().copied());
        let _ = run(
            root,
            "git",
            &reset,
            10,
            &AtomicBool::new(false),
            now_millis() + 10_000,
        );
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn root() -> PathBuf {
        let root = std::env::temp_dir().join(format!("workspace-execution-{}", Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        root.canonicalize().unwrap()
    }
    #[test]
    fn operations_use_selected_directory() {
        let root = root();
        let cancel = AtomicBool::new(false);
        let deadline = now_millis() + 10_000;
        execute(
            &root,
            "file_write",
            &json!({"path":"pom.xml", "content":"<project/>"}),
            &cancel,
            deadline,
        )
        .unwrap();
        let read = execute(
            &root,
            "file_read",
            &json!({"path":"pom.xml"}),
            &cancel,
            deadline,
        )
        .unwrap();
        assert_eq!(read["content"], "<project/>");
        let result = execute(
            &root,
            "file_search",
            &json!({"path":".", "mode":"NAME", "query":"**/pom.xml"}),
            &cancel,
            deadline,
        )
        .unwrap();
        assert_eq!(result["entries"].as_array().unwrap().len(), 1);
        #[cfg(unix)]
        {
            let pwd = command(&root, "pwd", 5, &cancel, deadline).unwrap();
            assert_eq!(
                pwd["stdout"].as_str().unwrap().trim(),
                root.to_str().unwrap()
            );
            let test = command(
                &root,
                "test -f pom.xml && printf 'test passed'",
                5,
                &cancel,
                deadline,
            )
            .unwrap();
            assert_eq!(test["exitCode"], 0);
        }
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn paths_cannot_escape_and_source_files_are_writable() {
        let root = root();
        assert!(path(&root, "../outside", true).is_err());
        assert!(path(&root, "/etc/passwd", false).is_err());
        assert!(path(&root, ".git/config", true).is_err());
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink("/tmp", root.join("escape")).unwrap();
            assert!(path(&root, "escape/file", true).is_err());
        }
        assert!(path(&root, "src/Main.java", true).is_ok());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[cfg(unix)]
    fn large_output_and_timeout_do_not_deadlock() {
        let root = root();
        let cancel = AtomicBool::new(false);
        let result = command(
            &root,
            "yes x | head -c 100000",
            5,
            &cancel,
            now_millis() + 10_000,
        )
        .unwrap();
        assert_eq!(result["truncated"], true);
        let result = command(&root, "sleep 10", 1, &cancel, now_millis() + 10_000).unwrap();
        assert_eq!(result["interrupted"], true);
        fs::remove_dir_all(root).unwrap();
    }
}

#[cfg(test)]
mod project_acceptance {
    use super::*;
    #[test]
    #[ignore = "explicitly runs a Maven test in the selected real project"]
    fn selected_agent_platform_executes_here() {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .canonicalize()
            .unwrap();
        let cancel = AtomicBool::new(false);
        let deadline = now_millis() + 120_000;
        let pwd = command(&root, "pwd", 10, &cancel, deadline).unwrap();
        assert_eq!(
            pwd["stdout"].as_str().unwrap().trim(),
            root.to_str().unwrap()
        );
        let pom = execute(
            &root,
            "file_read",
            &json!({"path":"pom.xml"}),
            &cancel,
            deadline,
        )
        .unwrap();
        assert!(pom["content"].as_str().unwrap().contains("<modules>"));
        let result = command(&root, "mvn -pl agentos-tool -am test -Dtest=RootedFileAccessPolicyTest -Dsurefire.failIfNoSpecifiedTests=false -q", 120, &cancel, deadline).unwrap();
        println!(
            "selected cwd: {}\nbranch: {}\npom.xml: read\nMaven result: {}",
            root.display(),
            branch(&root),
            result
        );
        assert_eq!(result["exitCode"], 0);
        assert_eq!(result["interrupted"], false);
    }
}
