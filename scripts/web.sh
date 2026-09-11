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
DEPLOY_BUILD_ID=""
BUILT_JAR_SHA=""
BACKEND_SOURCE_FINGERPRINT=""
FRONTEND_SOURCE_FINGERPRINT=""

log() {
    printf '[deploy] %s\n' "$*"
}

fail() {
    printf '[deploy] ERROR: %s\n' "$*" >&2
    exit 1
}

sha256_file() {
    shasum -a 256 "$1" | awk '{print $1}'
}

fingerprint_files() {
    local path
    local file
    {
        for path in "$@"; do
            if [[ -f "$path" ]]; then
                printf '%s\n' "$path"
            elif [[ -d "$path" ]]; then
                find "$path" -type f \
                    ! -path '*/target/*' \
                    ! -path '*/node_modules/*' \
                    ! -path '*/dist/*' \
                    ! -name '.DS_Store' -print
            fi
        done
    } | LC_ALL=C sort -u | while IFS= read -r file; do
        printf '%s  %s\n' "$(sha256_file "$file")" "$file"
    done | shasum -a 256 | awk '{print $1}'
}

backend_source_fingerprint() {
    fingerprint_files \
        "$PROJECT_ROOT/pom.xml" \
        "$PROJECT_ROOT/agentos-kernel/pom.xml" "$PROJECT_ROOT/agentos-kernel/src" \
        "$PROJECT_ROOT/agentos-tool/pom.xml" "$PROJECT_ROOT/agentos-tool/src" \
        "$PROJECT_ROOT/agentos-memory/pom.xml" "$PROJECT_ROOT/agentos-memory/src" \
        "$PROJECT_ROOT/agentos-hitl/pom.xml" "$PROJECT_ROOT/agentos-hitl/src" \
        "$PROJECT_ROOT/agentos-planner/pom.xml" "$PROJECT_ROOT/agentos-planner/src" \
        "$PROJECT_ROOT/agentos-agent/pom.xml" "$PROJECT_ROOT/agentos-agent/src" \
        "$PROJECT_ROOT/agentos-server/pom.xml" "$PROJECT_ROOT/agentos-server/src"
}

frontend_source_fingerprint() {
    fingerprint_files \
        "$CONSOLE_MODULE/package.json" "$CONSOLE_MODULE/package-lock.json" \
        "$CONSOLE_MODULE/index.html" "$CONSOLE_MODULE/vite.config.js" \
        "$CONSOLE_MODULE/src"
}

plist_environment_value() {
    /usr/libexec/PlistBuddy \
        -c "Print :EnvironmentVariables:$1" "$AGENT_PLIST" 2>/dev/null || true
}

backend_listener_pid() {
    lsof -nP -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

launch_agent_pid() {
    launchctl print "$LAUNCH_DOMAIN/$AGENT_LABEL" 2>/dev/null \
        | awk '$1 == "pid" && $2 == "=" {print $3; exit}'
}

wait_for_build_http() {
    local url="$1"
    local expected_build_id="$2"
    local attempts="${3:-120}"
    local count=1
    local body
    while [[ "$count" -le "$attempts" ]]; do
        body="$(curl -fsS "$url" 2>/dev/null || true)"
        if grep -Fq "\"buildId\":\"$expected_build_id\"" <<< "$body"; then
            return 0
        fi
        sleep 0.25
        count=$((count + 1))
    done
    return 1
}

wait_for_new_backend() {
    local previous_pid="$1"
    local expected_build_id="$2"
    local attempts="${3:-480}"
    local count=1
    local current_pid
    local job_pid
    local body
    while [[ "$count" -le "$attempts" ]]; do
        current_pid="$(backend_listener_pid)"
        job_pid="$(launch_agent_pid)"
        body="$(curl -fsS http://127.0.0.1:8080/api/health 2>/dev/null || true)"
        if [[ -n "$current_pid" \
                && -n "$job_pid" \
                && "$current_pid" == "$job_pid" \
                && ( -z "$previous_pid" || "$current_pid" != "$previous_pid" ) ]] \
                && grep -Fq "\"buildId\":\"$expected_build_id\"" <<< "$body"; then
            return 0
        fi
        sleep 0.25
        count=$((count + 1))
    done
    return 1
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

    # Desktop manifests must never be mistaken for enabled server process tools.
    if ! printf '%s' "$catalog" | node -e '
        let text = "";
        process.stdin.on("data", chunk => text += chunk);
        process.stdin.on("end", () => {
            const tools = JSON.parse(text).tools;
            if (!Array.isArray(tools)) process.exit(1);
            for (const name of ["run_command", "git_commit", "execute_code"]) {
                const tool = tools.find(item => item.name === name);
                if (tool && (name === "execute_code" || tool.desktopOnly !== true)) {
                    console.error("严格模式下仍启用了服务端进程工具：" + name);
                    process.exit(1);
                }
            }
        });
    '; then
        fail "服务端工具执行边界检查失败"
    fi
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
    command -v shasum >/dev/null || fail "找不到 shasum"
    command -v jar >/dev/null || fail "找不到 jar"
    command -v unzip >/dev/null || fail "找不到 unzip"
    command -v lsof >/dev/null || fail "找不到 lsof"
    [[ -x /usr/libexec/PlistBuddy ]] || fail "找不到 /usr/libexec/PlistBuddy"

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
    local source_before
    local embedded_build_id

    source_before="$(backend_source_fingerprint)"
    log "清理旧构建产物并运行后端完整测试"
    cd "$PROJECT_ROOT"
    mvn clean test

    log "打包 Spring Boot JAR"
    mvn -pl agentos-server -am package -DskipTests \
        -Dagentos.build.id="$DEPLOY_BUILD_ID"
    [[ -s "$BUILT_JAR" ]] || fail "JAR 未生成：$BUILT_JAR"

    BACKEND_SOURCE_FINGERPRINT="$(backend_source_fingerprint)"
    [[ "$source_before" == "$BACKEND_SOURCE_FINGERPRINT" ]] \
        || fail "后端源码在构建期间发生变化，请重新部署"
    BUILT_JAR_SHA="$(sha256_file "$BUILT_JAR")"
    embedded_build_id="$(unzip -p "$BUILT_JAR" \
        META-INF/build-info.properties 2>/dev/null \
        | awk -F= '$1 == "build.deployId" {print $2; exit}')"
    [[ "$embedded_build_id" == "$DEPLOY_BUILD_ID" ]] \
        || fail "JAR 构建标识不匹配：期望 $DEPLOY_BUILD_ID，实际 ${embedded_build_id:-missing}"

    # Maven 不执行 clean 时，已从源码删除的资源仍可能残留在 target/classes，
    # 并被重新打进 JAR。此检查避免旧 application.yml 污染部署配置。
    if [[ ! -f "$SERVER_MODULE/src/main/resources/application.yml" ]] \
        && jar tf "$BUILT_JAR" | grep -qx 'BOOT-INF/classes/application.yml'; then
        fail "JAR 包含源码中不存在的 application.yml，请检查残留构建产物"
    fi
}

run_frontend_build() {
    local source_before

    if [[ "$BACKEND_ONLY" == "true" ]]; then
        log "console 无变化，跳过前端构建与部署（--backend-only）"
        return
    fi
    source_before="$(frontend_source_fingerprint)"
    log "安装锁定的前端依赖"
    cd "$CONSOLE_MODULE"
    npm ci

    log "构建前端生产包"
    npm run build
    validate_frontend_dist
    FRONTEND_SOURCE_FINGERPRINT="$(frontend_source_fingerprint)"
    [[ "$source_before" == "$FRONTEND_SOURCE_FINGERPRINT" ]] \
        || fail "前端源码在构建期间发生变化，请重新部署"
}

smoke_test_packaged_jar() {
    local model_secret
    local datasource_url
    local datasource_username
    local datasource_password
    local validation
    local -a smoke_env

    log "在 127.0.0.1:$SMOKE_PORT 冒烟测试新 JAR"
    SMOKE_LOG="$(mktemp "${TMPDIR:-/tmp}/agentos-smoke.XXXXXX")"
    model_secret="$(plist_environment_value AGENTOS_MODEL_SECRET_KEY)"
    [[ -n "$model_secret" ]] \
        || fail "LaunchAgent 缺少 AGENTOS_MODEL_SECRET_KEY，无法验证已保存模型密钥"
    datasource_url="$(plist_environment_value SPRING_DATASOURCE_URL)"
    datasource_username="$(plist_environment_value SPRING_DATASOURCE_USERNAME)"
    datasource_password="$(plist_environment_value SPRING_DATASOURCE_PASSWORD)"
    smoke_env=(
        AGENTOS_AUTH_ENABLED=false
        AGENTOS_AUTH_ADMIN_PASSWORD=
        "AGENTOS_FILE_ACCESS_ROOT=$DEPLOY_ROOT"
        AGENTOS_ALLOW_HOST_PROCESSES=false
        AGENTOS_RUN_COMMAND_ENABLED=false
        AGENTOS_CODE_EXECUTOR_ENABLED=false
        AGENTOS_PERSISTENCE_MODE=postgresql
        "AGENTOS_MODEL_SECRET_KEY=$model_secret"
    )
    [[ -n "$datasource_url" ]] \
        && smoke_env+=("SPRING_DATASOURCE_URL=$datasource_url")
    [[ -n "$datasource_username" ]] \
        && smoke_env+=("SPRING_DATASOURCE_USERNAME=$datasource_username")
    [[ -n "$datasource_password" ]] \
        && smoke_env+=("SPRING_DATASOURCE_PASSWORD=$datasource_password")

    cd "$DEPLOY_ROOT"
    env "${smoke_env[@]}" \
        java -jar "$BUILT_JAR" \
        --server.address=127.0.0.1 \
        --server.port="$SMOKE_PORT" \
        >"$SMOKE_LOG" 2>&1 &
    SMOKE_PID="$!"

    if ! wait_for_build_http \
            "http://127.0.0.1:$SMOKE_PORT/api/health" "$DEPLOY_BUILD_ID" 120; then
        tail -n 80 "$SMOKE_LOG" >&2 || true
        fail "新 JAR 冒烟测试启动失败或构建标识不匹配"
    fi
    assert_safe_catalog "http://127.0.0.1:$SMOKE_PORT"
    if ! validation="$(curl --fail-with-body -sS \
            "http://127.0.0.1:$SMOKE_PORT/api/model-management/validate")"; then
        printf '[deploy] 模型配置校验响应：%s\n' "$validation" >&2
        tail -n 80 "$SMOKE_LOG" >&2 || true
        fail "新 JAR 模型配置校验失败"
    fi

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
    local previous_backend_pid
    local deployed_jar_sha

    [[ "$(backend_source_fingerprint)" == "$BACKEND_SOURCE_FINGERPRINT" ]] \
        || fail "后端源码在构建后发生变化，拒绝部署旧产物"
    if [[ "$BACKEND_ONLY" != "true" ]]; then
        [[ "$(frontend_source_fingerprint)" == "$FRONTEND_SOURCE_FINGERPRINT" ]] \
            || fail "前端源码在构建后发生变化，拒绝部署旧产物"
    fi

    timestamp="$(date '+%Y%m%d-%H%M%S')"
    backup_dir="$DEPLOY_ROOT/backups/$timestamp"
    jar_backup="$backup_dir/agentos-server.jar"
    console_backup="$backup_dir/console"

    log "备份当前版本到 $backup_dir"
    mkdir -p "$backup_dir" "$DEPLOY_ROOT/bin" "$DEPLOYED_CONSOLE"
    [[ -f "$DEPLOYED_JAR" ]] && cp -p "$DEPLOYED_JAR" "$jar_backup"
    if [[ "$BACKEND_ONLY" != "true" && -d "$DEPLOYED_CONSOLE" ]]; then
        mkdir -p "$console_backup"
        rsync -a "$DEPLOYED_CONSOLE/" "$console_backup/"
    fi

    if [[ "$BACKEND_ONLY" == "true" ]]; then
        log "保留现有前端目录（--backend-only）"
    else
        log "全量替换前端目录"
        rsync -a --delete "$CONSOLE_DIST/" "$DEPLOYED_CONSOLE/"
    fi

    log "原子替换后端 JAR"
    cp -p "$BUILT_JAR" "$STAGED_JAR"
    mv -f "$STAGED_JAR" "$DEPLOYED_JAR"
    deployed_jar_sha="$(sha256_file "$DEPLOYED_JAR")"
    if [[ "$deployed_jar_sha" != "$BUILT_JAR_SHA" ]]; then
        rollback "$jar_backup" "$console_backup"
        fail "部署后的 JAR 哈希与构建产物不一致，已回滚"
    fi

    previous_backend_pid="$(backend_listener_pid)"
    log "重启后端 LaunchAgent"
    if ! launchctl kickstart -k "$LAUNCH_DOMAIN/$AGENT_LABEL"; then
        rollback "$jar_backup" "$console_backup"
        fail "后端 LaunchAgent 重启失败"
    fi
    if [[ "$BACKEND_ONLY" != "true" ]]; then
        log "重启 Caddy LaunchAgent"
        if ! launchctl kickstart -k "$LAUNCH_DOMAIN/$CADDY_LABEL"; then
            rollback "$jar_backup" "$console_backup"
            fail "Caddy LaunchAgent 重启失败"
        fi
    fi

    # 冷启动实测可达 30s+（JVM 冷 page cache + launchd ThrottleInterval 节流 +
    # 旧进程优雅退出），窗口必须远大于 25s，否则会把刚启动成功的服务误判为失败并回滚。
    if ! wait_for_new_backend "$previous_backend_pid" "$DEPLOY_BUILD_ID" 480; then
        tail -n 100 "$DEPLOY_ROOT/logs/agentos-server.out.log" >&2 || true
        tail -n 100 "$DEPLOY_ROOT/logs/agentos-server.err.log" >&2 || true
        rollback "$jar_backup" "$console_backup"
        fail "新后端进程或构建标识验证失败，已回滚"
    fi
    if ! wait_for_http "http://127.0.0.1:3000/chat" 120; then
        tail -n 100 "$DEPLOY_ROOT/logs/caddy.err.log" >&2 || true
        rollback "$jar_backup" "$console_backup"
        fail "部署后的前端健康检查失败，已回滚"
    fi

    # 安全工具目录已在同一 JAR 的隔离冒烟进程中验证。生产服务启用 JWT 后，
    # 不应把管理员密码或 Token 注入部署脚本来重复访问受保护目录。
    rendered_html="$(curl -fsS -H 'Host: agent.touch-ai.tech' http://127.0.0.1:3000/chat)"
    grep -q '<script' <<< "$rendered_html" || {
        rollback "$jar_backup" "$console_backup"
        fail "Caddy Host 路由未返回有效前端页面，已回滚"
    }

    log "部署成功"
    log "构建标识：$DEPLOY_BUILD_ID"
    log "JAR SHA-256：$BUILT_JAR_SHA"
    log "后端：http://127.0.0.1:8080"
    log "前端：http://127.0.0.1:3000/chat"
    log "备份：$backup_dir"
}

# --backend-only：console 无变化时跳过前端构建与替换，只部署后端 JAR
BACKEND_ONLY=false
for arg in "$@"; do
    case "$arg" in
        --backend-only) BACKEND_ONLY=true ;;
        *) fail "未知参数：$arg（仅支持 --backend-only）" ;;
    esac
done

main() {
    preflight
    DEPLOY_BUILD_ID="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
    log "本次构建标识：$DEPLOY_BUILD_ID"
    run_backend_tests_and_package
    run_frontend_build
    smoke_test_packaged_jar
    deploy_release
}

main "$@"
