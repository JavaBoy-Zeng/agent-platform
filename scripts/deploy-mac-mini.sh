#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_ROOT="/Users/whale_fall/agentos"
SERVER_MODULE="$PROJECT_ROOT/agentos-server"
CONSOLE_MODULE="$PROJECT_ROOT/agentos-console"
BUILT_JAR="$SERVER_MODULE/target/agentos-server-0.0.1-SNAPSHOT.jar"
DEPLOYED_JAR="$DEPLOY_ROOT/bin/agentos-server.jar"
CONSOLE_DIST="$CONSOLE_MODULE/dist"
DEPLOYED_CONSOLE="$DEPLOY_ROOT/console"
AGENT_LABEL="tech.touch-ai.agentos"
CADDY_LABEL="tech.touch-ai.agentos-caddy"
LAUNCH_DOMAIN="gui/$(id -u)"
AGENT_PLIST="$HOME/Library/LaunchAgents/$AGENT_LABEL.plist"
CADDY_PLIST="$HOME/Library/LaunchAgents/$CADDY_LABEL.plist"
SMOKE_PORT="18080"
LOCK_DIR="${TMPDIR:-/tmp}/agentos-deploy-${UID}.lock"
SMOKE_LOG=""
SMOKE_PID=""
STAGED_JAR="$DEPLOY_ROOT/bin/.agentos-server.jar.new"

log() {
    printf '[deploy] %s\n' "$*"
}

fail() {
    printf '[deploy] ERROR: %s\n' "$*" >&2
    exit 1
}

cleanup() {
    if [[ -n "$SMOKE_PID" ]] && kill -0 "$SMOKE_PID" 2>/dev/null; then
        kill -TERM "$SMOKE_PID" 2>/dev/null || true
        wait "$SMOKE_PID" 2>/dev/null || true
    fi
    if [[ -n "$SMOKE_LOG" && -f "$SMOKE_LOG" ]]; then
        rm -f "$SMOKE_LOG"
    fi
    if [[ -f "$STAGED_JAR" ]]; then
        rm -f "$STAGED_JAR"
    fi
    rmdir "$LOCK_DIR" 2>/dev/null || true
}

trap cleanup EXIT INT TERM

wait_for_http() {
    local url="$1"
    local attempts="${2:-60}"
    local count=1
    local http_code
    while [[ "$count" -le "$attempts" ]]; do
        http_code="$(curl -sS -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
        if [[ "$http_code" == "200" ]]; then
            return 0
        fi
        sleep 0.25
        count=$((count + 1))
    done
    return 1
}

assert_safe_catalog() {
    local base_url="$1"
    local catalog
    local required_tool
    local forbidden_tool
    catalog="$(curl -fsS "$base_url/api/console/catalog")"

    for required_tool in file_read file_write directory_list file_search; do
        if ! grep -q "\"$required_tool\"" <<< "$catalog"; then
            fail "安全工具目录缺少 $required_tool"
        fi
    done

    for forbidden_tool in run_command git_commit execute_code; do
        if grep -q "\"$forbidden_tool\"" <<< "$catalog"; then
            fail "严格模式下仍注册了危险工具 $forbidden_tool"
        fi
    done
}

validate_frontend_dist() {
    [[ -f "$CONSOLE_DIST/index.html" ]] || fail "前端构建缺少 dist/index.html"
    [[ -d "$CONSOLE_DIST/assets" ]] || fail "前端构建缺少 dist/assets"
    find "$CONSOLE_DIST/assets" -maxdepth 1 -type f -print -quit | grep -q . \
        || fail "前端 dist/assets 为空"
    grep -q '<script' "$CONSOLE_DIST/index.html" \
        || fail "前端 index.html 没有脚本入口"
}

preflight() {
    local java_version
    local node_major

    [[ "$(uname -s)" == "Darwin" ]] || fail "该脚本只用于当前 Mac mini 部署"
    [[ -d "$DEPLOY_ROOT" ]] || fail "部署目录不存在：$DEPLOY_ROOT"
    mkdir "$LOCK_DIR" 2>/dev/null \
        || fail "已有部署任务在运行：$LOCK_DIR"
    [[ -f "$AGENT_PLIST" ]] || fail "后端 LaunchAgent 不存在：$AGENT_PLIST"
    [[ -f "$CADDY_PLIST" ]] || fail "Caddy LaunchAgent 不存在：$CADDY_PLIST"
    command -v mvn >/dev/null || fail "找不到 mvn"
    command -v java >/dev/null || fail "找不到 java"
    command -v node >/dev/null || fail "找不到 node"
    command -v npm >/dev/null || fail "找不到 npm"
    command -v curl >/dev/null || fail "找不到 curl"
    command -v rsync >/dev/null || fail "找不到 rsync"

    java_version="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
    case "$java_version" in
        21|21.*) ;;
        *) fail "需要 Java 21，当前为 ${java_version:-unknown}" ;;
    esac

    node_major="$(node --version | sed -E 's/^v([0-9]+).*/\1/')"
    [[ "$node_major" =~ ^[0-9]+$ ]] || fail "无法识别 Node.js 版本"
    [[ "$node_major" -ge 20 ]] || fail "需要 Node.js 20+，当前为 $(node --version)"

    plutil -lint "$AGENT_PLIST" >/dev/null
    plutil -lint "$CADDY_PLIST" >/dev/null
    grep -q '<string>/Users/whale_fall/agentos</string>' "$AGENT_PLIST" \
        || fail "LaunchAgent 未固定 AgentOS 根目录"

    if lsof -nP -iTCP:"$SMOKE_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        fail "冒烟测试端口 $SMOKE_PORT 已被占用"
    fi
}

run_backend_tests_and_package() {
    log "运行后端完整测试"
    cd "$PROJECT_ROOT"
    mvn test

    log "打包 Spring Boot JAR"
    mvn -pl agentos-server -am package -DskipTests
    [[ -s "$BUILT_JAR" ]] || fail "JAR 未生成：$BUILT_JAR"
}

run_frontend_build() {
    log "安装锁定的前端依赖"
    cd "$CONSOLE_MODULE"
    npm ci

    log "构建前端生产包"
    npm run build
    validate_frontend_dist
}

smoke_test_packaged_jar() {
    log "在 127.0.0.1:$SMOKE_PORT 冒烟测试新 JAR"
    SMOKE_LOG="$(mktemp "${TMPDIR:-/tmp}/agentos-smoke.XXXXXX")"
    cd "$DEPLOY_ROOT"
    env \
        AGENTOS_FILE_ACCESS_ROOT="$DEPLOY_ROOT" \
        AGENTOS_ALLOW_HOST_PROCESSES=false \
        AGENTOS_RUN_COMMAND_ENABLED=false \
        AGENTOS_CODE_EXECUTOR_ENABLED=false \
        AGENTOS_PERSISTENCE_MODE=memory \
        java -jar "$BUILT_JAR" \
        --server.address=127.0.0.1 \
        --server.port="$SMOKE_PORT" \
        >"$SMOKE_LOG" 2>&1 &
    SMOKE_PID="$!"

    if ! wait_for_http "http://127.0.0.1:$SMOKE_PORT/api/sessions" 80; then
        tail -n 80 "$SMOKE_LOG" >&2 || true
        fail "新 JAR 冒烟测试启动失败"
    fi
    assert_safe_catalog "http://127.0.0.1:$SMOKE_PORT"

    kill -TERM "$SMOKE_PID"
    wait "$SMOKE_PID" 2>/dev/null || true
    SMOKE_PID=""
    log "新 JAR 冒烟测试通过"
}

rollback() {
    local jar_backup="$1"
    local console_backup="$2"

    log "部署验证失败，开始回滚"
    if [[ -f "$jar_backup" ]]; then
        cp -p "$jar_backup" "$STAGED_JAR"
        mv -f "$STAGED_JAR" "$DEPLOYED_JAR"
    fi
    if [[ -d "$console_backup" ]]; then
        rsync -a --delete "$console_backup/" "$DEPLOYED_CONSOLE/"
    fi
    launchctl kickstart -k "$LAUNCH_DOMAIN/$AGENT_LABEL" || true
    launchctl kickstart -k "$LAUNCH_DOMAIN/$CADDY_LABEL" || true
}

deploy_release() {
    local timestamp
    local backup_dir
    local jar_backup
    local console_backup
    local rendered_html

    timestamp="$(date '+%Y%m%d-%H%M%S')"
    backup_dir="$DEPLOY_ROOT/backups/$timestamp"
    jar_backup="$backup_dir/agentos-server.jar"
    console_backup="$backup_dir/console"

    log "备份当前版本到 $backup_dir"
    mkdir -p "$backup_dir" "$DEPLOY_ROOT/bin" "$DEPLOYED_CONSOLE"
    [[ -f "$DEPLOYED_JAR" ]] && cp -p "$DEPLOYED_JAR" "$jar_backup"
    if [[ -d "$DEPLOYED_CONSOLE" ]]; then
        mkdir -p "$console_backup"
        rsync -a "$DEPLOYED_CONSOLE/" "$console_backup/"
    fi

    log "全量替换前端目录"
    rsync -a --delete "$CONSOLE_DIST/" "$DEPLOYED_CONSOLE/"

    log "原子替换后端 JAR"
    cp -p "$BUILT_JAR" "$STAGED_JAR"
    mv -f "$STAGED_JAR" "$DEPLOYED_JAR"

    log "重启后端和 Caddy LaunchAgent"
    if ! launchctl kickstart -k "$LAUNCH_DOMAIN/$AGENT_LABEL"; then
        rollback "$jar_backup" "$console_backup"
        fail "后端 LaunchAgent 重启失败"
    fi
    if ! launchctl kickstart -k "$LAUNCH_DOMAIN/$CADDY_LABEL"; then
        rollback "$jar_backup" "$console_backup"
        fail "Caddy LaunchAgent 重启失败"
    fi

    if ! wait_for_http "http://127.0.0.1:8080/api/sessions" 100; then
        tail -n 100 "$DEPLOY_ROOT/logs/agentos-server.out.log" >&2 || true
        tail -n 100 "$DEPLOY_ROOT/logs/agentos-server.err.log" >&2 || true
        rollback "$jar_backup" "$console_backup"
        fail "部署后的后端健康检查失败，已回滚"
    fi
    if ! wait_for_http "http://127.0.0.1:3000/chat" 60; then
        tail -n 100 "$DEPLOY_ROOT/logs/caddy.err.log" >&2 || true
        rollback "$jar_backup" "$console_backup"
        fail "部署后的前端健康检查失败，已回滚"
    fi

    assert_safe_catalog "http://127.0.0.1:8080"
    rendered_html="$(curl -fsS -H 'Host: agent.touch-ai.tech' http://127.0.0.1:3000/chat)"
    grep -q '<script' <<< "$rendered_html" || {
        rollback "$jar_backup" "$console_backup"
        fail "Caddy Host 路由未返回有效前端页面，已回滚"
    }

    log "部署成功"
    log "后端：http://127.0.0.1:8080"
    log "前端：http://127.0.0.1:3000/chat"
    log "备份：$backup_dir"
}

main() {
    preflight
    run_backend_tests_and_package
    run_frontend_build
    smoke_test_packaged_jar
    deploy_release
}

main "$@"
