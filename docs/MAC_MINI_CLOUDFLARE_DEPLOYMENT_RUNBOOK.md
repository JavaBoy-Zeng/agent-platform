# AgentOS Mac mini + Cloudflare Tunnel 部署与排障手册

> 文档基线：2026-08-24 当前部署
>
> 目标读者：后续维护这台 Mac mini 上 AgentOS 的本人或运维人员
>
> 目标：能够重新部署、启动、验证、升级，并根据 HTTP 状态码与响应内容快速定位故障

## 1. 当前部署结论

当前对外访问链路如下：

```text
浏览器
  │ HTTPS + Cloudflare Access
  ▼
agent.touch-ai.tech
  │ Cloudflare Tunnel
  ▼
cloudflared（macOS LaunchDaemon）
  │ HTTP，保留 Host: agent.touch-ai.tech
  ▼
127.0.0.1:3000（Caddy）
  ├── /api/*   ──► 127.0.0.1:8080（AgentOS Spring Boot）
  └── 其他路径 ──► ~/agentos/console（Vue 静态文件）
```

经过验证的关键配置：

| 项目 | 当前值 |
| --- | --- |
| 公网地址 | `https://agent.touch-ai.tech/chat` |
| Tunnel 名称 | `agentos-mac-mini` |
| Tunnel ID | `eeb7ff3d-c1e2-49e1-bdd9-9e1147c1f6c8` |
| Tunnel 源站 | `http://127.0.0.1:3000` |
| Caddy 监听 | `127.0.0.1:3000` |
| AgentOS 后端 | `127.0.0.1:8080` |
| 部署目录 | `/Users/whale_fall/agentos` |
| 后端 JAR | `/Users/whale_fall/agentos/bin/agentos-server.jar` |
| 前端目录 | `/Users/whale_fall/agentos/console` |
| Caddy 配置 | `/Users/whale_fall/agentos/Caddyfile` |
| cloudflared 错误日志 | `/Library/Logs/com.cloudflare.cloudflared.err.log` |

当前只有 cloudflared 已安装为系统 LaunchDaemon。Java 后端与 Caddy 仍是手动启动进程；Mac 重启后必须先启动 Java，再启动 Caddy，最后确认 cloudflared 已自动恢复。需要真正无人值守时，应再为 Java/Caddy 建立独立的 launchd 服务，不能假设 `nohup` 或 `caddy start` 能跨系统重启恢复。

## 2. 环境要求

### 2.1 AgentOS 本身

```bash
java -version
node --version
npm --version
```

建议：

- Java 21 LTS：运行和构建后端。
- Node.js 22：构建 Vue/Vite 控制台。
- Maven：构建多模块 Java 项目。
- Caddy：在本机统一代理前端和 `/api`。
- cloudflared：建立到 Cloudflare 的出站 Tunnel。

安装缺失组件：

```bash
brew install openjdk@21 node@22 maven caddy cloudflared

export PATH="/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/opt/node@22/bin:$PATH"
java -version
node --version
mvn -version
```

如需永久生效，把上面的 PATH 配置加入 `~/.zprofile`，然后重新打开终端。Intel Mac 的 Homebrew 前缀可能是 `/usr/local`；应先执行 `brew --prefix openjdk@21` 和 `brew --prefix node@22` 获取实际路径，不要照搬 Apple Silicon 路径。

### 2.2 `execute_code` 工具支持范围

当前代码中的 `execute_code` 只支持：

```text
python / shell / java
```

本机模式实际调用：

| 语言 | macOS 命令 |
| --- | --- |
| Python | `python3` |
| Shell | `/bin/sh` |
| Java | `java Main.java` |

检查命令：

```bash
python3 --version
java -version
/bin/sh --version 2>/dev/null || true
```

虽然当前 Mac 已安装 Node.js 和 Go，但现有 `CodeLanguage` 尚未支持 JavaScript/Go。仅安装运行时不会自动让 `execute_code` 支持它们；还需要扩展 `CodeLanguage`、本地执行命令、Docker 镜像映射与测试。

## 3. 首次构建与部署

以下命令在项目根目录执行：

```bash
cd /Users/whale_fall/developer/java/agent-platform
```

### 3.0 后续一键测试、构建与部署

首次安装和 LaunchAgent 配置完成后，后续发布直接执行：

```bash
cd /Users/whale_fall/developer/java/agent-platform
./scripts/web.sh
```

脚本会依次完成：后端完整测试、后端打包、`npm ci`、前端生产构建、新 JAR 独立端口
冒烟测试、当前版本完整备份、发布、重启后端与 Caddy，以及本地健康检查和危险工具目录检查。
任一发布后检查失败时，脚本会自动恢复本次备份并重新拉起服务。

前端采用全量镜像替换：`dist/` 之外的旧文件会通过 `rsync --delete` 删除，不保留旧哈希
资源，也不做增量发布。回滚备份仍保存完整的旧 `console/` 目录。

### 3.1 构建后端

先运行测试：

```bash
mvn test
```

再打包：

```bash
mvn -pl agentos-server -am package
```

准备部署目录并复制 JAR：

```bash
mkdir -p "$HOME/agentos/bin" "$HOME/agentos/console" "$HOME/agentos/logs"
cp agentos-server/target/agentos-server-0.0.1-SNAPSHOT.jar \
  "$HOME/agentos/bin/agentos-server.jar"
```

不要把真实 API Key 硬编码到提交的 YAML。推荐在启动环境中配置：

```bash
export AGENTOS_MODEL_API_KEY='替换为真实值'
```

每次打开新终端或 Mac 重启后，都需要从密码管理器或 macOS Keychain 重新加载运行环境；不要依赖上一次 shell 的临时 `export`。启动前只检查变量是否存在，不输出具体值：

```bash
test -n "${AGENTOS_MODEL_API_KEY:-}" \
  && echo 'AGENTOS_MODEL_API_KEY is set' \
  || echo 'AGENTOS_MODEL_API_KEY is missing'
```

当前控制台请求尚未自动携带 `X-API-Key`。因此直接设置 `AGENTOS_API_KEY` 会让页面中的 `/api/**` 请求变成 `401`。本部署先依赖 Cloudflare Access 保护公网入口；如需再启用后端 API Key，必须同时为控制台增加请求头支持，或在可信反向代理层安全注入请求头。

启动后端：

```bash
cd "$HOME/agentos"
AGENTOS_FILE_ACCESS_ROOT="/Users/whale_fall/agentos" \
AGENTOS_ALLOW_HOST_PROCESSES=false \
AGENTOS_RUN_COMMAND_ENABLED=false \
AGENTOS_CODE_EXECUTOR_ENABLED=false \
java -jar "$HOME/agentos/bin/agentos-server.jar" \
  --server.address=127.0.0.1 \
  --server.port=8080
```

当前 Mac mini 使用 LaunchAgent 托管后端，而不是依赖终端中的 `nohup`。配置文件位于：

```text
/Users/whale_fall/Library/LaunchAgents/tech.touch-ai.agentos.plist
```

其中 Java 必须使用 SDKMAN 的 Java 21 绝对路径。launchd 环境中的 `/usr/bin/java` 可能选中
Java 17，导致 `UnsupportedClassVersionError`。加载或更新服务：

```bash
plutil -lint "$HOME/Library/LaunchAgents/tech.touch-ai.agentos.plist"
launchctl bootout "gui/$(id -u)/tech.touch-ai.agentos" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" \
  "$HOME/Library/LaunchAgents/tech.touch-ai.agentos.plist"
launchctl print "gui/$(id -u)/tech.touch-ai.agentos"
```

这四个变量构成严格文件边界：内置文件工具会拒绝 `/etc`、其他 `/Users` 路径、`..`
遍历以及指向目录外的符号链接；Shell、Git 和宿主机本地代码执行默认不注册，防止绕过。
JVM 自身仍需读取 Java 运行时、系统动态库和证书等系统文件；这里限制的是 Agent 可调用的
文件/进程工具，不是 macOS 进程级沙箱。如需恢复代码执行，应使用 Docker 模式，不要开启
本地执行。

验证：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
curl -i http://127.0.0.1:8080/api/sessions
```

`lsof` 必须显示 `127.0.0.1:8080`，不能是 `*:8080`。预期 `/api/sessions` 返回 `200` 和 JSON；没有会话时通常为 `[]`。

### 3.2 构建前端

```bash
cd /Users/whale_fall/developer/java/agent-platform/agentos-console
npm ci
npm run build
rsync -a --delete dist/ "$HOME/agentos/console/"
```

确认 `index.html` 引用的带哈希文件确实存在：

```bash
rg 'assets/' "$HOME/agentos/console/index.html"
find "$HOME/agentos/console/assets" -maxdepth 1 -type f -print
```

每次重新构建后，Vite 生成的哈希文件名可能变化。必须复制完整 `dist/`，不能只替换 `index.html`。

## 4. Caddy 配置

正确的 `/Users/whale_fall/agentos/Caddyfile`：

```caddy
:3000 {
    bind 127.0.0.1

    handle /api/* {
        reverse_proxy 127.0.0.1:8080
    }

    handle {
        root * /Users/whale_fall/agentos/console
        try_files {path} /index.html
        file_server
    }
}
```

当前方案必须同时满足“接受 Tunnel 转发的 Host”和“只监听回环地址”。推荐的最简配置有两个关键点：

1. `:3000` 表示接受任意 HTTP `Host`，包括 Cloudflare 转发的 `agent.touch-ai.tech`。
2. `bind 127.0.0.1` 表示只监听本机回环地址，不向局域网直接开放端口。

### 4.1 为什么不能写成 `http://127.0.0.1:3000`

下面的配置看似更明确，但会把 `127.0.0.1` 变成站点 Host 匹配条件：

```caddy
http://127.0.0.1:3000 {
    bind 127.0.0.1
}
```

本机使用 `http://127.0.0.1:3000` 时正常；Cloudflare Tunnel 转发请求时保留：

```http
Host: agent.touch-ai.tech
```

本次部署中，Caddy 因 Host 不匹配返回了 `200` 和空响应。浏览器表面看到 `/chat 200 OK`，但 HTML/JS 实际为空，最终呈现白屏。不同 Caddy 版本或其他路由配置也可能返回不同的空响应或错误码，因此判断依据应是 Host 模拟请求的实际响应体，而不是只记住状态码。

### 4.2 启动、校验和重载

首次启动：

```bash
caddy start --config "$HOME/agentos/Caddyfile"
```

如果希望在前台观察日志：

```bash
caddy run --config "$HOME/agentos/Caddyfile"
```

当前 Caddy 同样由 LaunchAgent 托管，避免终端关闭或 Mac 重启后 3000 端口消失：

```text
/Users/whale_fall/Library/LaunchAgents/tech.touch-ai.agentos-caddy.plist
```

重新加载：

```bash
plutil -lint "$HOME/Library/LaunchAgents/tech.touch-ai.agentos-caddy.plist"
launchctl bootout "gui/$(id -u)/tech.touch-ai.agentos-caddy" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" \
  "$HOME/Library/LaunchAgents/tech.touch-ai.agentos-caddy.plist"
launchctl print "gui/$(id -u)/tech.touch-ai.agentos-caddy"
```

修改配置后无需重启 Java 或 cloudflared，执行：

```bash
caddy validate --config "$HOME/agentos/Caddyfile"
caddy reload --config "$HOME/agentos/Caddyfile"
```

验证监听地址：

```bash
lsof -nP -iTCP:3000 -sTCP:LISTEN
```

必须看到 `127.0.0.1:3000`，不应看到 `*:3000`。

## 5. Cloudflare Tunnel

### 5.1 安装为 macOS 系统服务

```bash
brew install cloudflared
sudo cloudflared service install '<TUNNEL_TOKEN>'
```

Tunnel Token 等同于持久访问凭据，不要写入仓库、文档、截图或聊天记录。直接把 Token 写在命令行还可能进入 shell history；安装时应从 Cloudflare Dashboard 临时复制，在可信终端中完成，随后检查并清理包含 Token 的历史记录。怀疑泄漏时不要只隐藏截图，应立即在 Dashboard 轮换 Token，并让服务使用新 Token 重新连接。

重新拉起服务：

```bash
sudo launchctl kickstart -k system/com.cloudflare.cloudflared
```

查看日志：

```bash
sudo tail -f /Library/Logs/com.cloudflare.cloudflared.err.log
```

健康状态应出现多条：

```text
Registered tunnel connection
```

单条 QUIC 连接偶发超时后成功重试通常不影响整体可用性。四个连接全部持续失败才需要检查网络、防火墙或代理。

日志中的下列信息在 Token 模式下通常不是致命错误：

```text
Cannot determine default origin certificate path
```

远程管理 Tunnel 使用 Token 连接时无需本地 `cert.pem`；判断是否健康应以 `Registered tunnel connection` 和 Dashboard 状态为准。

### 5.2 Published Route

Cloudflare Dashboard 中应配置：

```text
Hostname: agent.touch-ai.tech
Service:  http://127.0.0.1:3000
```

不要写成 `http://localhost:3000`。macOS 上 `localhost` 可能优先解析到 IPv6 `::1`，而 Caddy 只监听 IPv4 `127.0.0.1`。

验证差异：

```bash
curl -4 -I http://localhost:3000/
curl -6 -I http://localhost:3000/
curl -I http://127.0.0.1:3000/
```

### 5.3 Cloudflare Access

建议使用 Self-hosted Application 保护 `agent.touch-ai.tech`：

```text
Application: AgentOS Mac mini
Action:      Allow
Include:     指定个人邮箱
Login:       One-time PIN 或可信身份提供商
```

重置 Access 登录状态：

```text
https://agent.touch-ai.tech/cdn-cgi/access/logout
```

未登录时执行：

```bash
curl -I https://agent.touch-ai.tech/
```

收到 `302` 并跳转到 `*.cloudflareaccess.com` 是正常结果，说明 Access 正在保护站点，不代表 Tunnel 故障。

`CF_Authorization`、`CF_AppSession` 是登录凭据。排障时只记录状态码和响应头类型，不要复制完整 Cookie。

> **2026-08 起已迁移到应用内登录，建议移除 Cloudflare Access。**
> Access 的浏览器 SSO 依赖 Cookie，桌面壳（Tauri）的跨域 `fetch` 不会携带，
> 导致桌面端整体不可用（表现为 `Invalid CORS request` 或 302 跳登录）。
> 现在的门禁模型：后端多用户登录（JWT，`/api/auth/login` 签发，30 天免登录），
> web 控制台与桌面壳共用；脚本/机器调用走 `X-API-Key`。
> 迁移步骤：
> 1. plist 增加环境变量 `AGENTOS_AUTH_ADMIN_PASSWORD`（首个 ADMIN 引导密码）与
>    `AGENTOS_AUTH_SECRET`（`openssl rand -hex 32`，留空则重启后全员需重登）
> 2. 重启后端，日志确认 `[auth] authentication enabled`
> 3. Cloudflare Zero Trust 面板删除原 Access Application
> 4. 浏览器访问 → 登录页 → admin 登录 → 设置页创建日常用户
> 回滚：plist 去掉上述变量并重启，同时恢复 Access Application 即可。

### 5.4 Mac 重启后的恢复顺序

1. 从密码管理器或 macOS Keychain 加载模型密钥，并用非回显检查确认变量已设置。
2. 启动 Java 后端，命令必须包含 `--server.address=127.0.0.1`。
3. 验证 8080 的 JSON API。
4. 启动 Caddy，或确认 Caddy 已存在；验证 3000 和公网 Host 模拟请求。
5. 检查 cloudflared LaunchDaemon；正常情况下它会自动启动。
6. 最后从浏览器完成 Access 登录并检查 HTML、JS、CSS、API。

检查 cloudflared 服务状态：

```bash
sudo launchctl print system/com.cloudflare.cloudflared | sed -n '1,100p'
```

AgentOS Java 与 Caddy 已分别由 `tech.touch-ai.agentos`、`tech.touch-ai.agentos-caddy`
两个用户级 LaunchAgent 托管。需要手动重启时执行：

```bash
launchctl kickstart -k "gui/$(id -u)/tech.touch-ai.agentos"
launchctl kickstart -k "gui/$(id -u)/tech.touch-ai.agentos-caddy"
```

## 6. 每次启动后的健康检查

按从内到外的顺序检查，哪一层失败就停在哪一层处理。

### 第 1 层：后端

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
curl -i http://127.0.0.1:8080/api/sessions
```

### 第 2 层：Caddy 本地入口

```bash
lsof -nP -iTCP:3000 -sTCP:LISTEN
curl -sS -D /tmp/agentos-chat.headers \
  -o /tmp/agentos-chat.body \
  http://127.0.0.1:3000/chat
curl -sS http://127.0.0.1:3000/api/sessions
wc -c /tmp/agentos-chat.body
rg 'AgentOS / Mission Console' /tmp/agentos-chat.body
```

### 第 3 层：模拟 Cloudflare Host

这是本次白屏事件中最关键的检查：

```bash
curl -sS -D /tmp/agentos-host-chat.headers \
  -o /tmp/agentos-host-chat.body \
  -H 'Host: agent.touch-ai.tech' \
  http://127.0.0.1:3000/chat

asset_path=$(sed -n 's/.*src="\([^"]*\.js[^"]*\)".*/\1/p' \
  /tmp/agentos-host-chat.body | head -n 1)

curl -sS -D /tmp/agentos-host-js.headers \
  -o /tmp/agentos-host.js \
  -H 'Host: agent.touch-ai.tech' \
  "http://127.0.0.1:3000${asset_path}"

wc -c /tmp/agentos-host-chat.body /tmp/agentos-host.js
rg -i '^Content-Type:' \
  /tmp/agentos-host-chat.headers /tmp/agentos-host-js.headers
```

预期：

| 路径 | 状态 | Content-Type | 响应体特征 |
| --- | --- | --- | --- |
| `/chat` | `200` | `text/html` | 包含 `AgentOS / Mission Console` |
| `/assets/*.js` | `200` | `text/javascript` | 必须是非空 JavaScript；当前构建约 265 KB |
| `/assets/*.css` | `200` | `text/css` | 当前构建约 59 KB，不能是 HTML |
| `/api/sessions` | `200` | `application/json` | `[]` 或会话数组 |

文件名以部署目录中 `index.html` 的实际引用为准，不要长期依赖本表中的旧哈希名。

### 第 4 层：Tunnel

```bash
tail -n 100 /Library/Logs/com.cloudflare.cloudflared.err.log
```

确认：

- Tunnel 已注册连接。
- 最新配置中的源站为 `http://127.0.0.1:3000`。
- 没有持续出现 origin connect error。

### 第 5 层：公网与浏览器

```bash
curl -I https://agent.touch-ai.tech/chat
```

未携带 Access Cookie 时预期 `302`；这只能证明 Access 生效，不能证明 Tunnel 后面的源站健康。浏览器认证后还必须确认 HTML、实际哈希 JS/CSS 和 `/api/sessions` 均返回正确类型与非空内容。

浏览器中再检查：

1. `/chat` 的 `Status Code`。
2. `index-*.js` 的状态码、`Content-Type` 和传输大小。
3. Console 第一条红色错误。

### 第 6 层：真实回答流

使用后台运行接口验证控制台实际采用的链路。该命令会产生一次真实模型调用：

```bash
session_id="stream-check-$(date +%s)"
run_id="$(curl -fsS -X POST http://127.0.0.1:8080/api/agent-runs \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"${session_id}\",\"input\":\"请用两句话解释 JVM 是什么，并列出一个用途。\"}" \
  | jq -r '.runId')"

curl -N -D /tmp/agentos-stream.headers \
  "http://127.0.0.1:8080/api/agent-runs/${run_id}/events?after=0"
```

预期结果：

- 响应头包含 `Content-Type: text/event-stream`、`Cache-Control: no-cache, no-transform` 和 `X-Accel-Buffering: no`；
- 出现多个 `event:output_delta`，其中 `data.data.source` 为 `model-sse`；
- `output_delta` 在 `run_completed` 和最终 `state` 之前陆续出现；
- 增量长度由模型供应商决定，不应固定为 160 个字符。

复杂任务的规划 JSON 必须完整返回后才能校验，因此规划阶段没有 token 增量属于正常行为；进入最终回答阶段后才开始真实模型 SSE。`tool-result` 和 `runtime-result` 表示确定性结果，本来就没有模型 token，会作为单个增量返回。

## 7. 常见故障速查

### 7.1 `caddy: command not found`

```bash
brew install caddy
caddy version
```

### 7.2 Spring Boot 启动时报 `RunCommandTool could not be found`

典型错误：

```text
Parameter ... codeAgent ... required a bean of type RunCommandTool that could not be found
```

先检查：

```bash
echo "$AGENTOS_RUN_COMMAND_ENABLED"
```

处理方法：

- 使用已修复的新 JAR；当 `run-command` 被禁用时，依赖它的 `codeAgent` 也应条件化禁用。
- 或在确认安全风险后启用：`AGENTOS_RUN_COMMAND_ENABLED=true`。
- 不要为了启动成功而在公网环境无鉴权地开启高危 shell 工具。

重新构建并替换 JAR 后再启动。

### 7.3 Tunnel 日志出现 QUIC timeout

如果随后出现 `Registered tunnel connection`，通常只是某条边缘链路自动重试。

如果所有连接都失败：

1. 检查 Mac 是否能访问公网。
2. 临时检查代理/VPN规则。
3. 确认系统时间准确。
4. 重启 cloudflared 服务并再次看日志。

### 7.4 公网返回 Cloudflare 1033

1033 表示该 Host 所指向的 Tunnel 没有可用连接，或 DNS 仍指向旧 Tunnel。

排查：

1. Dashboard 中 Tunnel 是否 `Healthy`。
2. 该 Host 是否属于当前 Tunnel 的 Published Route。
3. DNS 记录是否仍指向旧的 `*.cfargotunnel.com`。
4. cloudflared 日志是否有已注册连接。

按发现的原因完成修复：

| 发现 | 修复动作 | 复验 |
| --- | --- | --- |
| 当前 Tunnel 没有连接 | 在 Mac 上执行 `sudo launchctl kickstart -k system/com.cloudflare.cloudflared`，再观察日志 | 日志出现 `Registered tunnel connection`，Dashboard 变为 `Healthy` |
| Published Route 缺少该 Host | Zero Trust → Networks → Tunnels → `agentos-mac-mini` → Published application routes，新增该 Host 并指向 `http://127.0.0.1:3000` | 日志出现 `Updated to new configuration`，公网不再返回 1033 |
| DNS 指向旧 Tunnel | 在 DNS Records 中把该 Host 的 Tunnel CNAME 重新关联到当前 Tunnel；确认目标 Tunnel ID 后再删除冲突的旧记录 | 等待 DNS 生效后重新请求该 Host |
| Token 被撤销或装错 Tunnel | 在 Dashboard 轮换当前 Tunnel Token，重新安装 cloudflared 服务 | 新 Connector 出现在当前 Tunnel，日志注册成功 |

Token 轮换会造成短暂中断。对于当前 Token 模式的 macOS LaunchDaemon，按 [Cloudflare Tunnel Token 官方流程](https://developers.cloudflare.com/tunnel/advanced/tunnel-tokens/#rotate-a-token)执行：

```bash
# 先在 Dashboard 对准确的 Tunnel 执行 Rotate token，并取得新 Token。
sudo cloudflared service uninstall
sudo cloudflared service install '<NEW_TUNNEL_TOKEN>'
sudo launchctl kickstart -k system/com.cloudflare.cloudflared
tail -n 100 /Library/Logs/com.cloudflare.cloudflared.err.log
```

复验时必须看到当前 Tunnel 的新 Connector 和 `Registered tunnel connection`。如果安装提示服务已经存在，不要反复执行 install，应先确认 `service uninstall` 是否成功，以及 `/Library/LaunchDaemons/com.cloudflare.cloudflared.plist` 是否仍属于旧服务。

1033 的含义是 Cloudflare 找不到该 Host 对应的健康 Connector。若 Connector 健康，但 Caddy 或 8080 不通，通常更可能看到源站连接失败或 `502`，应回到第 6 节检查本地链路。

`touch-ai.tech` 与 `agent.touch-ai.tech` 是两个独立 Host。只配置子域名不会让根域名自动可用。根域名更适合通过 Cloudflare Redirect Rule 跳转到 `https://agent.touch-ai.tech`，避免重复暴露源站。

### 7.5 `/chat` 返回 200，但页面白屏

不要看到 `200` 就停止排查。依次检查：

```bash
curl -sS -D - -o /tmp/agentos-chat.out \
  -H 'Host: agent.touch-ai.tech' \
  http://127.0.0.1:3000/chat

wc -c /tmp/agentos-chat.out
sed -n '1,12p' /tmp/agentos-chat.out
```

如果响应体是 0 字节，检查 Caddy 是否错误使用了：

```caddy
http://127.0.0.1:3000
```

修复为：

```caddy
:3000 {
    bind 127.0.0.1
}
```

然后：

```bash
caddy validate --config "$HOME/agentos/Caddyfile"
caddy reload --config "$HOME/agentos/Caddyfile"
```

再检查 JS：

```bash
asset_path=$(sed -n 's/.*src="\([^"]*\.js[^"]*\)".*/\1/p' \
  /tmp/agentos-chat.out | head -n 1)

curl -sS -D - -o /tmp/agentos.js \
  -H 'Host: agent.touch-ai.tech' \
  "http://127.0.0.1:3000${asset_path}"

wc -c /tmp/agentos.js
file /tmp/agentos.js
```

如果本地模拟 Host 正确而浏览器仍白屏：

1. DevTools → Network 勾选 `Disable cache`。
2. 使用 `Empty Cache and Hard Reload`。
3. 确认没有启用 `Disable JavaScript`。
4. 查看 JS 请求是否得到 `text/javascript`，不能得到 `text/html`。
5. 临时应急时可以给资源地址增加版本查询参数，但长期应重新构建产生新的 Vite 哈希文件，并为 `index.html` 配置不缓存或每次重新验证；不要把手工查询参数当成正式发布流程。

### 7.6 JS 请求返回 HTML

常见原因：

- 前端部署不完整，`index.html` 引用的哈希文件不存在，`try_files` 回退到了 `index.html`。
- Access 会话失效，资源请求拿到登录页。
- 浏览器或边缘缓存了旧响应。

检查：

```bash
rg 'assets/' "$HOME/agentos/console/index.html"
find "$HOME/agentos/console/assets" -maxdepth 1 -type f -print
```

### 7.7 浏览器显示 `Remote Address: 127.0.0.1:7897`

这表示 Chrome 正在经过本机代理软件，不表示 AgentOS 在 7897 端口运行。真正的本地链路仍是：

```text
cloudflared → 127.0.0.1:3000 → 127.0.0.1:8080
```

如怀疑代理问题，可以对比：

```bash
curl -I https://agent.touch-ai.tech/
curl -x http://127.0.0.1:7897 -I https://agent.touch-ai.tech/
```

### 7.8 `localhost:3000` 不通，但 `127.0.0.1:3000` 正常

```bash
curl -4 -I http://localhost:3000/
curl -6 -I http://localhost:3000/
```

如果 IPv6 失败，Tunnel 源站固定写 `http://127.0.0.1:3000`，不要依赖 `localhost` 的解析顺序。

## 8. 升级与回滚

当前 Java/Caddy 尚未交给 launchd 管理，因此升级时先用 `lsof` 找到 8080 的准确 PID，只停止该 PID；不要使用 `pkill java`，避免误停其他 Java 程序。停止后确认 8080 已释放，再备份 SQLite 和替换文件。

本手册固定 Java 工作目录为 `$HOME/agentos`，因此默认 SQLite 相对路径也以该目录为基准。升级命令使用独立的绝对源码路径变量，避免 `cd` 后丢失构建产物：

```bash
project_root="/Users/whale_fall/developer/java/agent-platform"
test -d "$project_root/agentos-server"
test -d "$project_root/agentos-console"
```

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
ps -p <确认后的PID> -ww -o pid=,command=
kill -TERM <确认后的PID>

# 只有下面命令不再输出监听进程，才可以继续替换或恢复文件。
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

创建同一批次的后端与前端备份：

```bash
release_stamp=$(date +%Y%m%d-%H%M%S)
backup_dir="$HOME/agentos/backups/$release_stamp"
mkdir -p "$backup_dir"
cp "$HOME/agentos/bin/agentos-server.jar" "$backup_dir/agentos-server.jar"
ditto "$HOME/agentos/console" "$backup_dir/console"
```

如果使用 SQLite 持久化，先进入后端实际工作目录，再确定 `AGENTOS_PERSISTENCE_SQLITE_FILE`。相对路径是相对于 Java 进程工作目录，不是相对于源码目录：

```bash
cd "$HOME/agentos"
sqlite_file="${AGENTOS_PERSISTENCE_SQLITE_FILE:-.agentos/runtime/runtime.sqlite}"
test -s "$sqlite_file"
sqlite3 "$sqlite_file" ".backup '$backup_dir/runtime.sqlite'"
test -s "$backup_dir/runtime.sqlite"
```

不要在数据库仍写入时直接复制，也不要假设数据库结构一定向后兼容。涉及 schema 迁移的版本必须先确认旧程序能否读取新数据库；不能确认时，回滚 JAR 的同时回滚数据库副本。

将新文件先放到临时名称，验证存在后再切换：

```bash
cp "$project_root/agentos-server/target/agentos-server-0.0.1-SNAPSHOT.jar" \
  "$HOME/agentos/bin/agentos-server.jar.next"
test -s "$HOME/agentos/bin/agentos-server.jar.next"
mv "$HOME/agentos/bin/agentos-server.jar.next" \
  "$HOME/agentos/bin/agentos-server.jar"

mkdir -p "$HOME/agentos/console.next"
rsync -a "$project_root/agentos-console/dist/" "$HOME/agentos/console.next/"
test -s "$HOME/agentos/console.next/index.html"
mv "$HOME/agentos/console" "$HOME/agentos/console.previous-$release_stamp"
mv "$HOME/agentos/console.next" "$HOME/agentos/console"
```

前端切换完成后旧目录仍保留，可恢复，不要立即递归删除。JAR 文件名可能随项目版本变化，执行前应先通过 `find "$project_root/agentos-server/target" -name '*.jar'` 确认实际产物。

推荐升级顺序：

1. `mvn test`。
2. 构建新 JAR 和前端 `dist/`。
3. 备份当前 JAR/前端。
4. 替换后端并重启 Java。
5. 同步前端文件；静态文件无需重启 Caddy。
6. 执行第 6 节五层健康检查。
7. 失败时恢复备份 JAR/前端并重新验证。

回滚示例（将时间戳替换为实际备份目录）：

```bash
# 先按本节开头的步骤确认并停止当前 Java，确保 8080 已释放。
rollback_dir="$HOME/agentos/backups/20260824-000000"
test -s "$rollback_dir/agentos-server.jar"
test -s "$rollback_dir/console/index.html"
cp "$rollback_dir/agentos-server.jar" "$HOME/agentos/bin/agentos-server.jar"
mv "$HOME/agentos/console" "$HOME/agentos/console.failed-$(date +%Y%m%d-%H%M%S)"
ditto "$rollback_dir/console" "$HOME/agentos/console"

# 仅当本次版本涉及 SQLite schema/数据不兼容时恢复数据库。
cd "$HOME/agentos"
sqlite_file="${AGENTOS_PERSISTENCE_SQLITE_FILE:-.agentos/runtime/runtime.sqlite}"
test -s "$rollback_dir/runtime.sqlite"
if [ -e "$sqlite_file" ]; then
  mv "$sqlite_file" "$sqlite_file.failed-$(date +%Y%m%d-%H%M%S)"
fi
cp "$rollback_dir/runtime.sqlite" "$sqlite_file"
```

随后重新加载模型密钥、启动 Java，并完整执行第 6 节健康检查。Caddy 读取静态目录中的新文件，不需要因前端切换而重启。

## 9. 安全基线

- Caddy 必须继续 `bind 127.0.0.1`，不要为了省事监听 `0.0.0.0`。
- Tunnel Token、模型 API Key、`CF_Authorization` Cookie 不得写入文档或提交 Git。

文件访问采用两级判断：规划器先读取运行时的 `allowedRoot`，用户明确给出的绝对路径
位于 `/Users/whale_fall/agentos` 外时直接返回拒绝结果，计划中应显示 `steps=0`、
`toolCalls=0`；位于根目录内的请求才会调用文件工具。工具执行前仍会再次执行
`RootedFileAccessPolicy` 授权，用于拦截 `..`、真实路径越界和指向根目录外的符号链接。

部署根目录内的配置文件还有第三层强制保护。`ProtectedConfigurationFileAccessPolicy`
在工具层执行，无法通过提示词绕过：

- `directory_list` 不展示配置文件名；
- `file_search` 的 `NAME` 模式不返回配置文件名，`CONTENT` 模式不会打开或扫描配置文件；
- `file_read` 和 `file_write` 对配置文件直接返回 `SECURITY_DENIED`；
- 符号链接最终指向受保护配置文件时同样被拒绝；
- 拒绝结果使用固定消息，不回显配置文件名或内容。

当前保护范围包括 `.env`、`.env.*`、`.envrc`、`Caddyfile`、`Dockerfile`，以及
`.cfg`、`.cnf`、`.conf`、`.config`、`.ini`、`.json`、`.properties`、`.toml`、
`.xml`、`.yaml`、`.yml`、`.plist` 和常见证书/密钥后缀。该限制是服务端代码策略，
不是前端隐藏，也没有提供生产环境关闭开关。服务启动阶段仍可通过仅供内部装配使用的
根目录策略加载必要配置，但该策略没有注册为 Agent 工具，模型无法调用。
配置文件的 `.bak`、`.backup-*`、`.old`、`.orig`、编辑器 `~` 等常见备份形式也按
原配置后缀识别，避免历史副本成为旁路。

验证越界请求不会调用工具：

```bash
curl -X POST http://127.0.0.1:8080/api/agents/runs \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"verify-boundary","input":"列出 /Users/whale_fall/agentos 的上级目录 /Users/whale_fall/"}'
```

预期状态为 `COMPLETED`，回答说明只允许访问 `/Users/whale_fall/agentos`，日志中该次
MainAgent 运行显示 `steps=0 toolCalls=0`。
- 公网部署必须保留 Cloudflare Access，且 Allow Policy 只包含必要账号。
- 当前控制台不会发送 `X-API-Key`；不要只设置 `AGENTOS_API_KEY`，否则 UI 会因 `/api/**` 返回 `401` 而不可用。若要增加第二层鉴权，应先同步改造控制台或可信反向代理。
- `run_command` 和本地 `execute_code` 能直接操作 Mac 文件系统，属于高风险能力。
- 更安全的代码执行方式是 Docker 沙箱；使用 `auto/docker` 前先准备镜像并验证限制。
- 不要在排障截图中展开请求 Cookie、Authorization Header 或 Tunnel Token。

## 10. 一分钟恢复清单

```bash
# 1. 后端是否存活
lsof -nP -iTCP:8080 -sTCP:LISTEN
curl -i http://127.0.0.1:8080/api/sessions

# 2. Caddy 是否存活
lsof -nP -iTCP:3000 -sTCP:LISTEN

# 3. Caddy 配置是否正确
caddy validate --config "$HOME/agentos/Caddyfile"
caddy reload --config "$HOME/agentos/Caddyfile"

# 4. 必须用公网 Host 模拟一次
curl -sS -D /tmp/agentos-quick.headers -o /tmp/agentos-quick.html \
  -H 'Host: agent.touch-ai.tech' \
  http://127.0.0.1:3000/chat
wc -c /tmp/agentos-quick.html
rg 'AgentOS / Mission Console' /tmp/agentos-quick.html

asset_path=$(sed -n 's/.*src="\([^"]*\.js[^"]*\)".*/\1/p' \
  /tmp/agentos-quick.html | head -n 1)
curl -sS -D /tmp/agentos-quick-js.headers -o /tmp/agentos-quick.js \
  -H 'Host: agent.touch-ai.tech' \
  "http://127.0.0.1:3000${asset_path}"
wc -c /tmp/agentos-quick.js
rg -i '^Content-Type:.*javascript' /tmp/agentos-quick-js.headers

# 5. 检查 Tunnel
tail -n 100 /Library/Logs/com.cloudflare.cloudflared.err.log

# 6. 公网检查（未登录时 302 到 Access 属于正常）
curl -I https://agent.touch-ai.tech/chat
```

最终判断标准不是“某个请求返回 200”，而是：HTML、JS、CSS、API 都返回正确类型和非空内容，且使用 `Host: agent.touch-ai.tech` 的本机模拟请求也能通过。
