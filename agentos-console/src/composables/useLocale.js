import { computed, ref } from 'vue'

const STORAGE_KEY = 'agentos.console.locale.v1'
const SUPPORTED_LOCALES = new Set(['zh', 'en'])

const english = {
  '跳到操作区': 'Skip to workspace',
  '系统状态': 'System status',
  '当前时间': 'Current time',
  '新建会话': 'New session',
  '界面语言': 'Interface language',
  '切换为英文': 'Switch to English',
  '切换为中文': 'Switch to Chinese',
  'AgentOS 首页': 'AgentOS home',
  'Console 主导航': 'Console navigation',
  '会话列表': 'Session list',
  '会话': 'Sessions',
  '会话名称': 'Session title',
  '更多操作': 'More actions',
  '更多操作：{title}': 'More actions for {title}',
  '选择': 'Select',
  '退出多选': 'Exit selection',
  '全选': 'Select all',
  '取消全选': 'Clear selection',
  '已选 {count} 个': '{count} selected',
  '删除所选': 'Delete selected',
  '选择会话：{title}': 'Select session: {title}',
  '运行中的会话不可选择': 'A running session cannot be selected',
  '重命名': 'Rename',
  '删除': 'Delete',
  '删除会话': 'Delete session',
  '运行中的会话无法删除': 'A running session cannot be deleted',
  '加载更早会话': 'Load earlier sessions',
  '正在读取会话…': 'Loading sessions…',
  '新建任务通道': 'New task channel',
  '删除这个会话？': 'Delete this session?',
  '批量删除会话？': 'Delete selected sessions?',
  '选中的 {count} 个会话将从服务端会话列表中移除。为满足审计要求，已生成的运行事件仍按系统留存策略保存。': '{count} selected sessions will be removed from the server session list. Existing run events remain subject to the system retention policy for audit purposes.',
  '删除 {count} 个会话': 'Delete {count} sessions',
  '“{title}”将从服务端会话列表中移除。为满足审计要求，已生成的运行事件仍按系统留存策略保存。': '“{title}” will be removed from the server session list. Existing run events remain subject to the system retention policy for audit purposes.',
  '取消': 'Cancel',
  '会话未能从服务端删除。': 'The session could not be deleted from the server.',
  '部分会话未能从服务端删除。': 'Some sessions could not be deleted from the server.',
  '会话重命名未能保存到服务端。': 'The new session title could not be saved.',
  '无法读取服务端会话，当前显示浏览器缓存。': 'Server history is unavailable. Showing the browser cache.',
  '未命名任务': 'Untitled task',
  '暂无会话': 'No sessions',
  '任务指令': 'Task instruction',
  '描述目标、限制条件和期望结果……': 'Describe the goal, constraints, and expected result…',
  '示例指令': 'Example prompts',
  '分析任务': 'Analyze task',
  '汇总结论': 'Summarize',
  '检查风险': 'Check risks',
  '分析当前任务并给出下一步行动建议': 'Analyze the current task and recommend the next action',
  '汇总本次会话的关键结论': 'Summarize the key conclusions from this session',
  '检查执行计划中的潜在风险': 'Check the execution plan for potential risks',
  '执行任务': 'Run task',
  '停止任务': 'Stop task',
  '执行': 'Run',
  '换行': 'New line',
  '运行记录': 'Run transcript',
  '清空记录': 'Clear transcript',
  '清空视图': 'Clear view',
  '等待首条指令': 'Waiting for the first instruction',
  '当前通道已建立。输入任务以启动 Agent Loop。': 'The channel is ready. Enter a task to start the Agent Loop.',
  '暂无运行记录': 'No run transcript yet',
  '提交任务后，运行过程和结果会显示在这里。': 'Run progress and results will appear here after you submit a task.',
  '意图进入，': 'Intent in.',
  '行动发生。': 'Action out.',
  '向主 Agent 下达任务。运行时将建立上下文、生成计划、调用工具，并留下可追踪的状态结果。': 'Give the main Agent a task. The runtime builds context, plans, invokes tools, and leaves a traceable result.',
  '运行遥测': 'Run telemetry',
  '运行时空闲，等待任务输入。': 'Runtime idle. Waiting for a task.',
  'Agent Loop 正在处理当前任务。': 'The Agent Loop is processing the current task.',
  '高风险操作正在等待人工批准。': 'A high-risk action is waiting for human approval.',
  '任务执行完成，状态已归档。': 'Task completed and state archived.',
  '当前任务已被取消。': 'The current task was cancelled.',
  '任务执行失败，请检查运行记录。': 'Task failed. Check the run transcript.',
  '等待运行时状态。': 'Waiting for runtime status.',
  '处理中': 'Processing',
  '建立本次运行上下文': 'Build run context',
  '生成最小可执行计划': 'Generate a minimal executable plan',
  '调用注册工具': 'Invoke registered tools',
  '摘要化工具执行结果': 'Summarize tool results',
  '完成或继续规划': 'Complete or continue planning',
  '等待人工审批': 'Waiting for human approval',
  '需要批准操作': 'Approval required',
  '已批准并恢复执行': 'Approved and resumed',
  '已拒绝': 'Rejected',
  '批准': 'Approve',
  '拒绝': 'Reject',
  '批准并继续': 'Approve and continue',
  '工具': 'Tool',
  '目标': 'Target',
  '模式': 'Mode',
  '仓库': 'Repository',
  '提交说明': 'Commit message',
  '提交文件': 'Committed files',
  'Agent Loop 正在运行': 'Agent Loop is running',
  'MEDIUM+ 需要人工批准': 'MEDIUM+ requires human approval',
  '查看运行时注册的 Agent 拓扑、形态与工具化状态。': 'Inspect registered Agent topology, roles, and tool exposure.',
  '检查服务端后台运行的状态、迭代次数与事件游标。': 'Inspect background run status, iterations, and event cursors.',
  '浏览服务端会话快照、状态键与最后活动时间。': 'Browse server session snapshots, state keys, and recent activity.',
  '审计运行时已注册工具、风险等级与参数。': 'Audit registered tools, risk levels, and parameters.',
  '观察 MCP Server 配置和传输状态。': 'Inspect MCP server configuration and transport status.',
  '查看可按需注入 Agent 上下文的技能目录。': 'Browse skills that can be injected into Agent context on demand.',
  '沿 L0–L3 检查最近对话、原子记忆、场景与画像。': 'Inspect recent turns, atomic memories, scenarios, and profiles across L0–L3.',
  '按领域事件复盘计划创建、步骤执行与重规划轨迹。': 'Review planning, step execution, and replanning through domain events.',
  '以 Span 时间线定位一次调用链的耗时与故障。': 'Use the span timeline to locate latency and failures in a trace.',
  '下载或治理 Agent 在运行中登记的文件产物。': 'Download and manage files registered during Agent runs.',
  '集中处理被风险策略挂起的外部动作。': 'Review external actions paused by risk policies.',
  '查看模型路由、Provider 与当前会话 Token 用量。': 'Inspect model routing, providers, and session token usage.',
  '对单次执行回放工具轨迹，校验路径而不只校验答案。': 'Replay a run trajectory and verify the path, not only the answer.',
  '搜索': 'Search',
  '刷新数据': 'Refresh data',
  '正在加载': 'Loading',
  '重试': 'Retry',
  '当前范围没有可展示的数据。运行一个 Agent 任务后再刷新此面板。': 'There is no data in the current scope. Run an Agent task, then refresh this panel.',
  '先在 Chat 中创建一个会话': 'Create a session in Chat first',
  '在 Chat 中打开': 'Open in Chat',
  '该会话不在本地档案中': 'This session is not in the local cache',
  '该层暂未形成记忆': 'No memory has formed in this layer yet',
  '会话工作记忆': 'Session working memory',
  '稳定事实与偏好': 'Stable facts and preferences',
  '可复用任务经验': 'Reusable task experience',
  '长期核心画像': 'Long-term core profile',
  '不限制': 'No limit',
  '结论, 建议': 'conclusion, recommendation',
  '要求运行成功收口': 'Require a completed run',
  '评估中…': 'Evaluating…',
  '运行评估': 'Run evaluation',
  '查看完整结果': 'View full result',
  '轨迹优先的回归校验': 'Trajectory-first regression checks',
  '最终答案正确不代表执行路径正确。选择一次 Invocation，声明期望的工具序列、禁用工具与调用预算，服务端会回放已存储的领域事件逐项比对。': 'A correct final answer does not guarantee a correct execution path. Select an invocation and define the expected tool sequence, forbidden tools, and call budget; the server will replay stored domain events and compare each check.',
  '当前会话还没有已存储的执行事件，先在 Chat 中运行一次任务。': 'This session has no stored execution events yet. Run a task in Chat first.',
  '外部动作等待确认': 'External action awaiting confirmation',
  '该动作需要人工确认后才能继续运行。': 'This action needs human confirmation before the run can continue.',
  '详情检查器': 'Object inspector',
  '关闭详情': 'Close details',
  '删除产物？': 'Delete artifact?',
  '确认删除': 'Delete permanently',
  '将永久删除 {filename}，下载链接会立即失效。此操作不可撤销。': '{filename} will be permanently deleted and its download link will stop working immediately. This cannot be undone.',
  '失败 {count}': '{count} failed',
  '无法读取 Console 数据': 'Unable to load console data',
  '评估请求失败': 'Evaluation request failed',
  '删除产物失败': 'Artifact deletion failed',
  '审批处理失败': 'Approval update failed',
  '任务已完成。': 'Task completed.',
  '任务已取消。': 'Task cancelled.',
  'Agent 未能完成任务。': 'The Agent could not complete the task.',
  '操作已拒绝，任务已停止。': 'The action was rejected and the task stopped.',
  '已请求停止当前任务。': 'A stop request was sent for the current task.',
  '后台运行记录已不存在，可能是服务端发生过重启。': 'The background run no longer exists. The server may have restarted.',
  '无法连接 AgentOS Server': 'Unable to connect to AgentOS Server',
  '处理审批操作失败': 'Approval handling failed',
  '停止任务失败': 'Failed to stop the task'
}

function detectInitialLocale() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (SUPPORTED_LOCALES.has(stored)) return stored
  } catch {
    // Storage may be unavailable in privacy-restricted contexts.
  }
  return String(globalThis.navigator?.language || '').toLowerCase().startsWith('en') ? 'en' : 'zh'
}

const locale = ref(detectInitialLocale())

function applyDocumentLanguage(value) {
  if (globalThis.document?.documentElement) {
    document.documentElement.lang = value === 'en' ? 'en' : 'zh-CN'
  }
}

applyDocumentLanguage(locale.value)

export function useLocale() {
  const localeTag = computed(() => locale.value === 'en' ? 'en-US' : 'zh-CN')
  const isEnglish = computed(() => locale.value === 'en')

  function setLocale(value) {
    if (!SUPPORTED_LOCALES.has(value) || value === locale.value) return
    locale.value = value
    applyDocumentLanguage(value)
    try {
      localStorage.setItem(STORAGE_KEY, value)
    } catch {
      // The in-memory preference still applies for the current page.
    }
  }

  function toggleLocale() {
    setLocale(locale.value === 'zh' ? 'en' : 'zh')
  }

  function t(source, params = {}) {
    let value = locale.value === 'en' ? (english[source] || source) : source
    for (const [key, replacement] of Object.entries(params)) {
      value = value.replaceAll(`{${key}}`, () => String(replacement))
    }
    return value
  }

  return { locale, localeTag, isEnglish, setLocale, toggleLocale, t }
}
