import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ModelManagementPanel from './ModelManagementPanel.vue'
import { createModelProvider } from '../services/consoleApi.js'

vi.mock('../services/consoleApi.js', () => ({
  getModelManagement: vi.fn(async () => ({ providers: [], models: [] })),
  createModelProvider: vi.fn(async () => ({})),
  updateModelProvider: vi.fn(async () => ({})),
  deleteModelProvider: vi.fn(async () => ({})),
  testModelProvider: vi.fn(async () => ({ message: 'Connected', latencyMs: 12 }))
}))

let wrapper

afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})

function dialogElement(selector) {
  const element = document.body.querySelector(selector)
  if (!element) throw new Error(`Missing dialog element: ${selector}`)
  return element
}

describe('ModelManagementPanel provider editor', () => {
  it('shows only the essential fields and keeps advanced configuration collapsed initially', async () => {
    wrapper = mount(ModelManagementPanel, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.add-provider').trigger('click')

    expect(dialogElement('#providerEditorTitle').textContent).toBe('添加内置模型')
    expect(dialogElement('.model-kind-switch').textContent).toContain('内置模型')
    dialogElement('.model-kind-switch button:last-child').click()
    await flushPromises()
    expect(dialogElement('#providerEditorTitle').textContent).toBe('添加自定义模型')
    expect(dialogElement('.editor-primary').textContent).toContain('OpenAI Chat Completions 格式')
    expect(dialogElement('.editor-primary').textContent).toContain('自定义请求地址')
    expect(dialogElement('.editor-primary').textContent).toContain('模型 ID')
    expect(dialogElement('.advanced-toggle').getAttribute('aria-expanded')).toBe('false')
    expect(document.body.querySelector('#providerAdvancedPanel')).toBeNull()
    expect(dialogElement('.model-backdrop').closest('[data-v-app]')).toBeNull()
  })

  it('expands advanced configuration from the accessible toggle', async () => {
    wrapper = mount(ModelManagementPanel, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.add-provider').trigger('click')
    dialogElement('.advanced-toggle').click()
    await flushPromises()

    expect(dialogElement('.advanced-toggle').getAttribute('aria-expanded')).toBe('true')
    expect(dialogElement('#providerAdvancedPanel').textContent).toContain('上下文窗口')
    expect(dialogElement('#providerAdvancedPanel').textContent).toContain('工具调用轮数')
    expect(dialogElement('#providerAdvancedPanel').textContent).toContain('思考模式')
    expect(dialogElement('#providerAdvancedPanel').textContent).toContain('采样参数')
    expect(dialogElement('#providerAdvancedPanel').textContent).toContain('连接与兼容性')
    expect(dialogElement('#providerAdvancedPanel').textContent).toContain('Planner 响应格式')
  })

  it('offers DeepSeek, GLM and Qwen presets and fills the selected model', async () => {
    wrapper = mount(ModelManagementPanel, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.add-provider').trigger('click')
    dialogElement('[aria-labelledby="providerTypeLabel"]').click()
    await flushPromises()

    const options = [...document.body.querySelectorAll('[role="option"]')]
    expect(options.map(option => option.textContent)).toEqual(expect.arrayContaining([
      expect.stringContaining('DeepSeek'),
      expect.stringContaining('GLM'),
      expect.stringContaining('Qwen')
    ]))
    options.find(option => option.textContent.includes('DeepSeek')).click()
    await flushPromises()

    expect(dialogElement('#providerEditorTitle').textContent).toBe('添加内置模型')
    expect(dialogElement('[aria-labelledby="defaultModelLabel"]').textContent).toContain('deepseek-v4-pro')
  })

  it('unmounts the provider dialog after a successful create', async () => {
    wrapper = mount(ModelManagementPanel, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.add-provider').trigger('click')
    dialogElement('.model-kind-switch button:last-child').click()
    await flushPromises()

    const dialog = dialogElement('.provider-editor')
    dialog.querySelector('.custom-address-field input[type="url"]').value = 'https://api.example.com/v1'
    dialog.querySelector('.custom-address-field input[type="url"]').dispatchEvent(new Event('input', { bubbles: true }))
    dialog.querySelector('input[placeholder="输入模型 ID"]').value = 'example-model'
    dialog.querySelector('input[placeholder="输入模型 ID"]').dispatchEvent(new Event('input', { bubbles: true }))
    dialog.querySelector('.api-key-field input').value = 'test-key'
    dialog.querySelector('.api-key-field input').dispatchEvent(new Event('input', { bubbles: true }))
    dialog.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(document.body.querySelector('.model-backdrop')).toBeNull()
  })

  it('keeps the dialog mounted and shows the server error when create fails', async () => {
    vi.mocked(createModelProvider).mockRejectedValueOnce(new Error('providerType 不受支持'))
    wrapper = mount(ModelManagementPanel, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.add-provider').trigger('click')
    dialogElement('.model-kind-switch button:last-child').click()
    await flushPromises()

    const dialog = dialogElement('.provider-editor')
    dialog.querySelector('input[placeholder="输入模型 ID"]').value = 'example-model'
    dialog.querySelector('input[placeholder="输入模型 ID"]').dispatchEvent(new Event('input', { bubbles: true }))
    dialog.querySelector('.api-key-field input').value = 'test-key'
    dialog.querySelector('.api-key-field input').dispatchEvent(new Event('input', { bubbles: true }))
    dialog.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(document.body.querySelector('.model-backdrop')).not.toBeNull()
    expect(dialogElement('.provider-editor-error').textContent).toContain('providerType 不受支持')
  })

  it('does not close the editor when the backdrop is clicked', async () => {
    wrapper = mount(ModelManagementPanel, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.add-provider').trigger('click')

    dialogElement('.model-backdrop').dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    await flushPromises()

    expect(document.body.querySelector('.provider-editor')).not.toBeNull()
  })

  it('asks before discarding edited provider fields', async () => {
    wrapper = mount(ModelManagementPanel, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.add-provider').trigger('click')
    dialogElement('.model-kind-switch button:last-child').click()
    await flushPromises()

    const modelInput = dialogElement('.provider-editor input[placeholder="输入模型 ID"]')
    modelInput.value = 'edited-model'
    modelInput.dispatchEvent(new Event('input', { bubbles: true }))
    dialogElement('.editor-close').click()
    await flushPromises()

    expect(dialogElement('.discard-dialog').textContent).toContain('放弃本次修改')
    expect(document.body.querySelector('.provider-editor')).not.toBeNull()

    dialogElement('.discard-dialog button').click()
    await flushPromises()
    expect(document.body.querySelector('.discard-dialog')).toBeNull()
    expect(document.body.querySelector('.provider-editor')).not.toBeNull()
  })
})
