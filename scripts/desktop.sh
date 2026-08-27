#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONSOLE_MODULE="$PROJECT_ROOT/agentos-console"
DESKTOP_MODULE="$PROJECT_ROOT/agentos-desktop"
TAURI_MODULE="$DESKTOP_MODULE/src-tauri"
BUILT_APP="$TAURI_MODULE/target/release/bundle/macos/AgentOS.app"
INSTALLED_APP="/Applications/AgentOS.app"
BACKUP_ROOT="/Users/whale_fall/agentos/backups"
SERVER_URL="${AGENTOS_DESKTOP_SERVER_URL:-https://agent.touch-ai.tech}"
LOCK_DIR="${TMPDIR:-/tmp}/agentos-desktop-deploy-${UID}.lock"
STAGE_DIR=""
STAGED_APP=""
BACKUP_DIR=""
BACKUP_APP=""
INSTALL_STARTED=false
BUILD_ONLY=false
LAUNCH_AFTER_INSTALL=true

log() {
    printf '[desktop-deploy] %s\n' "$*"
}

fail() {
    printf '[desktop-deploy] ERROR: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
用法：
  ./scripts/deploy-desktop-mac.sh [--build-only] [--no-launch]

选项：
  --build-only  只测试并构建 AgentOS.app，不安装到 /Applications。
  --no-launch   安装完成后不自动启动 AgentOS。
  -h, --help    显示帮助。
EOF
}

cleanup() {
    if [[ -n "$STAGE_DIR" && -d "$STAGE_DIR" ]]; then
        rm -rf "$STAGE_DIR"
    fi
    rmdir "$LOCK_DIR" 2>/dev/null || true
}

rollback() {
    if [[ "$INSTALL_STARTED" != true ]]; then
        return
    fi
    log "安装失败，开始恢复上一版本"
    if [[ -d "$INSTALLED_APP" && -n "$BACKUP_DIR" ]]; then
        mv "$INSTALLED_APP" "$BACKUP_DIR/AgentOS.failed.app" 2>/dev/null || true
    fi
    if [[ -n "$BACKUP_APP" && -d "$BACKUP_APP" ]]; then
        mv "$BACKUP_APP" "$INSTALLED_APP" 2>/dev/null || true
        log "上一版本已恢复到 $INSTALLED_APP"
    fi
}

on_error() {
    local exit_code="$1"
    rollback
    exit "$exit_code"
}

trap 'on_error $?' ERR
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

parse_args() {
    while [[ "$#" -gt 0 ]]; do
        case "$1" in
            --build-only) BUILD_ONLY=true ;;
            --no-launch) LAUNCH_AFTER_INSTALL=false ;;
            -h|--help) usage; exit 0 ;;
            *) fail "未知参数：$1" ;;
        esac
        shift
    done
}

preflight() {
    local rust_version
    local node_major

    [[ "$(uname -s)" == "Darwin" ]] || fail "该脚本仅支持 macOS"
    mkdir "$LOCK_DIR" 2>/dev/null || fail "已有桌面部署任务在运行：$LOCK_DIR"
    [[ -f "$CONSOLE_MODULE/package-lock.json" ]] || fail "缺少前端 package-lock.json"
    [[ -f "$DESKTOP_MODULE/package-lock.json" ]] || fail "缺少桌面端 package-lock.json"
    [[ -f "$TAURI_MODULE/Cargo.lock" ]] || fail "缺少 Cargo.lock"

    for required_command in node npm cargo rustc curl ditto plutil codesign; do
        command -v "$required_command" >/dev/null || fail "找不到 $required_command"
    done

    node_major="$(node --version | sed -E 's/^v([0-9]+).*/\1/')"
    [[ "$node_major" =~ ^[0-9]+$ && "$node_major" -ge 20 ]] \
        || fail "需要 Node.js 20+，当前为 $(node --version)"

    rust_version="$(rustc --version | awk '{print $2}')"
    [[ -n "$rust_version" ]] || fail "无法识别 Rust 版本"

    if [[ "$BUILD_ONLY" != true ]]; then
        [[ -d "/Applications" && -w "/Applications" ]] \
            || fail "当前用户不可写 /Applications"
        [[ -d "$BACKUP_ROOT" ]] || fail "备份根目录不存在：$BACKUP_ROOT"
    fi
}

run_tests_and_build() {
    log "安装锁定的 Vue 依赖并运行测试"
    cd "$CONSOLE_MODULE"
    npm ci
    npm test

    log "安装锁定的 Tauri CLI 依赖"
    cd "$DESKTOP_MODULE"
    npm ci

    log "运行 Rust 格式、测试与检查"
    cd "$TAURI_MODULE"
    cargo fmt --check
    cargo test --locked
    cargo check --locked

    log "构建绑定 $SERVER_URL 的 AgentOS.app"
    cd "$DESKTOP_MODULE"
    AGENTOS_DESKTOP_SERVER_URL="$SERVER_URL" npm run build
}

validate_app() {
    local app_path="$1"
    local executable

    [[ -d "$app_path" ]] || fail "桌面产物不存在：$app_path"
    [[ -f "$app_path/Contents/Info.plist" ]] || fail "桌面产物缺少 Info.plist"
    plutil -lint "$app_path/Contents/Info.plist" >/dev/null
    executable="$(plutil -extract CFBundleExecutable raw "$app_path/Contents/Info.plist")"
    [[ -x "$app_path/Contents/MacOS/$executable" ]] \
        || fail "桌面产物缺少可执行文件：$executable"
    if [[ -f "$app_path/Contents/_CodeSignature/CodeResources" ]]; then
        codesign --verify --deep --strict "$app_path"
    else
        log "提示：当前为未签名开发包，未执行 Developer ID 签名校验"
    fi
}

quit_running_app() {
    local executable_path="$INSTALLED_APP/Contents/MacOS/agentos-desktop"
    local pids
    local attempt

    pids="$(pgrep -f "^${executable_path}$" || true)"
    [[ -z "$pids" ]] && return

    log "正在退出运行中的 AgentOS"
    kill -TERM $pids 2>/dev/null || true
    for attempt in {1..40}; do
        pgrep -f "^${executable_path}$" >/dev/null 2>&1 || return
        sleep 0.25
    done
    fail "AgentOS 未能正常退出，请手动按 Command+Q 后重试"
}

install_app() {
    local timestamp

    STAGE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agentos-desktop-stage.XXXXXX")"
    STAGED_APP="$STAGE_DIR/AgentOS.app"
    log "复制并验证待安装应用"
    ditto "$BUILT_APP" "$STAGED_APP"
    validate_app "$STAGED_APP"

    timestamp="$(date '+%Y%m%d-%H%M%S')"
    BACKUP_DIR="$BACKUP_ROOT/$timestamp/desktop"
    BACKUP_APP="$BACKUP_DIR/AgentOS.app"
    mkdir -p "$BACKUP_DIR"

    quit_running_app
    INSTALL_STARTED=true
    if [[ -d "$INSTALLED_APP" ]]; then
        log "备份当前桌面端到 $BACKUP_APP"
        mv "$INSTALLED_APP" "$BACKUP_APP"
    fi

    log "安装新版桌面端到 $INSTALLED_APP"
    mv "$STAGED_APP" "$INSTALLED_APP"
    validate_app "$INSTALLED_APP"
    INSTALL_STARTED=false

    if [[ "$LAUNCH_AFTER_INSTALL" == true ]]; then
        log "启动 AgentOS"
        open "$INSTALLED_APP"
    fi

    log "桌面端部署成功"
    log "应用：$INSTALLED_APP"
    if [[ -d "$BACKUP_APP" ]]; then
        log "备份：$BACKUP_APP"
    fi
}

main() {
    parse_args "$@"
    preflight
    run_tests_and_build
    validate_app "$BUILT_APP"

    if [[ "$BUILD_ONLY" == true ]]; then
        log "桌面端构建成功"
        log "产物：$BUILT_APP"
        return
    fi

    curl -fsS "$SERVER_URL/api/health" >/dev/null \
        || fail "远端 AgentOS Server 不可达：$SERVER_URL"
    install_app
}

main "$@"
