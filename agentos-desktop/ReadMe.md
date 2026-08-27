# AgentOS Desktop

基于 [Tauri 2](https://tauri.app) 的 macOS 桌面壳：复用 [`agentos-console`](../agentos-console/ReadMe.md)
的 Vue 3 控制台，通过可配置地址直连本地或远端 `agentos-server`。壳内不做任何业务逻辑。

```text
AgentOS.app (Tauri WKWebView)
    └─ 加载 agentos-console/dist 静态资源（生产）或 Vite dev server（开发）
            │  HTTP + SSE，地址在控制台「设置」页配置
            ▼
   agentos-server（本地 mvn spring-boot:run，或远端部署）
```

## 前置条件

- Node.js ≥ 20 与 npm
- Rust 工具链（`rustup` 安装；首次构建会编译约 2–5 分钟并下载依赖）

## 本地开发

```bash
# 1. 启动后端（或直接使用远端部署的地址）
cd agentos-platform
mvn -pl agentos-server -am spring-boot:run

# 2. 启动桌面壳（会先自动拉起 agentos-console 的 Vite dev server :5173）
cd agentos-desktop
npm install
npm run dev     # 即 tauri dev
```

开发模式窗口加载 `http://localhost:5173`，前端热更新照常生效。
server 地址默认走 Vite 同源代理；要连其他实例，在应用内打开「设置」（侧边栏 SYSTEM 分组）
填写完整地址，例如 `http://192.168.x.x:8080`。

## 连接与登录

桌面壳打开后先进入登录页（与 web 控制台共用同一套多用户账号，JWT 30 天免登录）。
Server 地址在「设置 → 连接配置」写入本机 localStorage，立即生效：

| 配置项 | 存储 Key | 说明 |
| --- | --- | --- |
| Server 地址 | `agentos.server-url` | 留空 = 与页面同源；打包版默认 `https://agent.touch-ai.tech`（构建期 `VITE_API_BASE_URL` 烧入） |
| 登录令牌 | `agentos.auth-token` | 登录成功自动写入，登出清除 |

顶部横幅每 30 秒探测一次 `/api/health`（放行路径），不可达时给出引导提示。

## 打包分发

日常构建并安装到本机 `/Applications`，直接运行项目脚本：

```bash
cd /Users/whale_fall/developer/java/agent-platform
./scripts/desktop.sh
```

脚本会运行 Vue/Rust 测试、构建 `AgentOS.app`、备份已安装版本、原子替换应用并重新启动。
只生成产物而不安装时使用 `--build-only`；安装后不自动启动时使用 `--no-launch`。

手动构建仍可使用：

```bash
cd agentos-desktop
npm run build   # 即 tauri build
```

产物位于 `src-tauri/target/release/bundle/`：

- `macos/AgentOS.app`
- `dmg/AgentOS_<版本>_<架构>.dmg`

当前未做签名与公证（需要 Apple Developer 账号）。接收方首次打开方式：

- 右键 `AgentOS.app` → 「打开」；或
- 终端执行 `xattr -cr /Applications/AgentOS.app` 后再启动

## 跨域（CORS）

桌面 WebView 的页面 origin 为 `tauri://localhost`（Windows 下为 `http://tauri.localhost`），
server 已默认放行这两个来源；如需覆盖：

```bash
AGENTOS_CORS_ALLOWED_ORIGINS="tauri://localhost" mvn -pl agentos-server -am spring-boot:run
# 或配置 agentos.cors.allowed-origins，值为 * 时放行全部
```

## 后续规划

- Apple 签名与公证（消除首次打开放行步骤）
- 自动更新（Tauri updater，依赖签名密钥）
- Windows/Linux 目标（能力上已具备，按需开启 bundle targets）
