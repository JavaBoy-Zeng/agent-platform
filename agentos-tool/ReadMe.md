# agentos-tool

`agentos-tool` 定义 Agent 可调用能力的统一协议、执行运行时、内置工具和结构化失败类型。

## 包结构

| 包 | 职责 |
| --- | --- |
| `com.github.agentos.tool.api` | 对外稳定的工具协议、调用、定义、结果和执行模式。 |
| `com.github.agentos.tool.runtime` | 工具注册、统一调度、执行上下文和生命周期拦截器。 |
| `com.github.agentos.tool.builtin` | AgentOS 默认提供的工具实现。 |
| `com.github.agentos.tool.builtin.file` | 文件工具，以及 access、reader、writer 格式适配器。 |
| `com.github.agentos.tool.builtin.git` | 本地 Git 工具。 |

`api` 不依赖 `runtime` 或 `builtin`，`runtime` 不依赖 `builtin`，这些边界由 ArchUnit 测试持续校验。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `AgentTool` | 工具扩展接口，声明名称、说明、参数、风险等级和执行方法。 |
| `ToolParameter / ToolDefinition` | 同时供模型 Schema 和运行前校验使用的参数结构。 |
| `ToolCall` | 工具名称和不可变参数 Map。 |
| `ToolResult` | 成功输出，或带 `ToolFailureType` 的结构化失败。 |
| `ToolRegistry` | 线程安全地注册、查找和枚举工具。 |
| `ToolDispatcher` | 统一处理工具解析、拦截器、异常转换、并行调度和事件发布。 |
| `FileAccessPolicy` | 所有文件工具共用的路径授权抽象。 |
| `PagedFileReader / PagedReadResult` | 按物理页和页内偏移读取文件，并显式返回续读位置。 |

当前服务端装配 `AllowAllReadableFileAccessPolicy`，允许读取本机任意可读路径，不限制 workspace。
未来可以替换为 sandbox 策略而不修改工具实现。

## 内置工具

| 工具 | 用途和边界 |
| --- | --- |
| `directory_list` | 有界列出目录；深度默认 2、最大 5，条目默认 200、最大 500，不跟随符号链接。 |
| `file_search` | `NAME` glob 或 `CONTENT` 字面量搜索；深度默认 8、最大 12，结果默认 100、最大 500。 |
| `file_read` | 在路径已确认后读取文件；PDF 按物理页和页内偏移分页，并返回续读元数据。 |
| `echo` | 仅用于调用链测试，不承担最终回答。 |
| `weather` | 查询外部天气接口。 |

`file_search` 最多扫描 20,000 个文件。内容搜索会跳过符号链接、二进制、不可读和大于 1 MiB 的
文件，并返回带行号的匹配结果。

PDF 首次调用 `file_read` 时可省略 `page` 和 `offset`，默认从第 1 页、页内偏移 0 开始。
每次正文最多返回 3000 字符，并同时返回 `totalPages`、`hasMore`、`nextPage`、
`nextOffset` 和 `truncated`。完整读取任务必须持续使用下一位置，直至 `hasMore=false`。

## 失败分类约定

工具应尽量返回明确的 `ToolFailureType`：

- 参数错误：`INVALID_ARGUMENT`
- 资源不存在：`NOT_FOUND`
- 短暂网络或服务故障：`TRANSIENT`
- 访问、权限或安全策略拒绝：对应 `ACCESS_DENIED / PERMISSION_DENIED / SECURITY_DENIED`
- 工具实现 Bug：`TOOL_INTERNAL_ERROR`

`UNKNOWN` 默认由 Runtime 终止，避免规划器掩盖没有正确分类的工具问题。
