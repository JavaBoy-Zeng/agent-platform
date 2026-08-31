import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AutomationView from './AutomationView.vue'
import {
  createAutomation,
  deleteAutomation,
  getAutomationExecutions,
  getAutomations,
  previewAutomationSchedule
} from '../services/automationApi.js'

const routerPush = vi.fn()

vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))
vi.mock('../services/automationApi.js', () => ({
  createAutomation: vi.fn(async body => ({ automationId: 'created', ...body })),
  deleteAutomation: vi.fn(async () => null),
  getAutomationExecutions: vi.fn(async () => ({ items: [], total: 0, offset: 0, limit: 20, hasMore: false })),
  getAutomations: vi.fn(async () => []),
  previewAutomationSchedule: vi.fn(async () => ({
    summary: '每天 09:00',
    nextOccurrences: ['2026-08-31T01:00:00Z', '2026-09-01T01:00:00Z', '2026-09-02T01:00:00Z']
  })),
  runAutomation: vi.fn(async () => ({})),
  setAutomationEnabled: vi.fn(async () => ({})),
  updateAutomation: vi.fn(async () => ({}))
}))

let wrapper
let consoleState
let desktopWorkspace
let automationDesktop

const task = {
  automationId: 'automation-1',
  name: '每日代码摘要',
  prompt: '汇总代码变化',
  modelId: 'model-1',
  approvalMode: 'RISK_BASED',
  workspaceId: 'workspace-1',
  workspaceName: 'agent-platform',
  enabled: true,
  nextTriggerAt: '2026-08-31T01:00:00Z',
  trigger: {
    type: 'PERIOD', periodMode: 'BASIC', periodUnit: 'DAILY', time: '09:00', timeZone: 'Asia/Shanghai'
  }
}

function mountView() {
  wrapper = mount(AutomationView, {
    attachTo: document.body,
    global: { provide: { agentConsole: consoleState, desktopWorkspace, automationDesktop } }
  })
  return wrapper
}

function bodyElement(selector) {
  const element = document.body.querySelector(selector)
  if (!element) throw new Error(`Missing element: ${selector}`)
  return element
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getAutomations).mockResolvedValue([])
  vi.mocked(getAutomationExecutions).mockResolvedValue({ items: [], total: 0, offset: 0, limit: 20, hasMore: false })
  consoleState = {
    models: ref([{ id: 'model-1', name: 'GPT Preview', provider: 'Local', providerType: 'OPENAI' }]),
    refreshServerModel: vi.fn(async () => {}),
    refreshSessions: vi.fn(async () => {}),
    selectSession: vi.fn(async () => {})
  }
  desktopWorkspace = {
    available: ref(true),
    workspaces: ref([{ id: 'workspace-1', name: 'agent-platform', gitRepository: true }]),
    authorize: vi.fn(async () => {}),
    automationFileIndex: vi.fn(async () => [{ name: 'README.md', relativePath: 'README.md' }])
  }
  automationDesktop = {
    desktop: true,
    clientId: ref('desktop-1'),
    online: ref(true),
    keepAwake: ref(false),
    keepAwakeSupported: ref(true),
    setKeepAwake: vi.fn(async enabled => { automationDesktop.keepAwake.value = enabled })
  }
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('AutomationView', () => {
  it('opens an accessible editor and switches between period, cron and interval rules', async () => {
    mountView()
    await flushPromises()
    await wrapper.get('.create-automation').trigger('click')
    await flushPromises()

    expect(bodyElement('.automation-dialog').getAttribute('aria-modal')).toBe('true')
    expect(bodyElement('#automationDialogTitle').textContent).toBe('新建自动化任务')
    expect(document.activeElement).toBe(bodyElement('input[placeholder="例如：每日代码变更摘要"]'))

    const periodModeButtons = [...document.body.querySelectorAll('[aria-label="周期模式"] button')]
    periodModeButtons.find(button => button.textContent.includes('高级 Cron')).click()
    await flushPromises()
    expect(bodyElement('.cron-field input').getAttribute('placeholder')).toBe('0 9 * * 1-5')

    const triggerButtons = [...document.body.querySelectorAll('[aria-label="触发类型"] button')]
    triggerButtons.find(button => button.textContent === '间隔').click()
    await flushPromises()
    expect(bodyElement('input[aria-label="间隔数量"]').value).toBe('30')
  })

  it('validates and creates a task with the selected desktop and workspace', async () => {
    mountView()
    await flushPromises()
    await wrapper.get('.create-automation').trigger('click')
    await flushPromises()

    const name = bodyElement('input[placeholder="例如：每日代码变更摘要"]')
    const prompt = bodyElement('textarea[placeholder="描述目标、限制条件和期望结果……"]')
    name.value = '每日代码摘要'
    name.dispatchEvent(new Event('input', { bubbles: true }))
    prompt.value = '读取 @README.md 并汇总变化'
    prompt.dispatchEvent(new Event('input', { bubbles: true }))
    bodyElement('.save-button').click()
    await flushPromises()

    expect(previewAutomationSchedule).toHaveBeenCalled()
    expect(createAutomation).toHaveBeenCalledWith(expect.objectContaining({
      name: '每日代码摘要',
      prompt: '读取 @README.md 并汇总变化',
      desktopClientId: 'desktop-1',
      workspaceId: 'workspace-1',
      modelId: 'model-1',
      approvalMode: 'RISK_BASED',
      enabled: true
    }))
    expect(document.body.querySelector('.automation-dialog')).toBeNull()
  })

  it('uses a custom delete confirmation and opens execution sessions in Chat', async () => {
    vi.mocked(getAutomations).mockResolvedValue([task])
    vi.mocked(getAutomationExecutions).mockResolvedValue({
      items: [{
        executionId: 'execution-1', taskName: task.name, workspaceName: task.workspaceName,
        status: 'COMPLETED', triggerSource: 'SCHEDULED', scheduledAt: '2026-08-31T01:00:00Z',
        startedAt: '2026-08-31T01:00:01Z', finishedAt: '2026-08-31T01:00:09Z', sessionId: 'session-1'
      }],
      total: 1, offset: 0, limit: 20, hasMore: false
    })
    mountView()
    await flushPromises()

    await wrapper.get('[aria-label="更多操作"]').trigger('click')
    const deleteMenuItem = [...document.body.querySelectorAll('[role="menuitem"]')]
      .find(button => button.textContent.includes('删除任务'))
    deleteMenuItem.click()
    await flushPromises()
    expect(bodyElement('.automation-confirm').textContent).toContain('执行历史和 Chat 会话仍会保留')
    bodyElement('.automation-confirm button.danger').click()
    await flushPromises()
    expect(deleteAutomation).toHaveBeenCalledWith('automation-1')

    const historyTab = [...wrapper.findAll('.automation-tabs button')]
      .find(button => button.text().includes('执行历史'))
    await historyTab.trigger('click')
    await flushPromises()
    await wrapper.get('.history-table td:last-child button').trigger('click')

    expect(consoleState.refreshSessions).toHaveBeenCalled()
    expect(consoleState.selectSession).toHaveBeenCalledWith('session-1')
    expect(routerPush).toHaveBeenCalledWith('/chat')
  })
})
