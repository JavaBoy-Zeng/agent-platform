import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CommandDeck from './CommandDeck.vue'

function mountDeck(overrides = {}) {
  return mount(CommandDeck, {
    props: {
      agentId: 'main-agent',
      sessionId: 'session-1',
      prompt: 'hello',
      currentModel: { id: 'model-deepseek', name: 'deepseek-v4-pro', provider: 'DeepSeek', modelType: 'BUILT_IN' },
      models: [
        { key: 'model-deepseek', id: 'model-deepseek', name: 'deepseek-v4-pro', provider: 'DeepSeek', providerType: 'DEEPSEEK', modelType: 'BUILT_IN' },
        { key: 'model-custom', id: 'model-custom', name: 'my-model', provider: 'Private gateway', providerType: 'OPENAI_COMPATIBLE', modelType: 'CUSTOM' }
      ],
      selectedModelKey: 'model-deepseek',
      approvalMode: 'FULL_ACCESS',
      uploadAvailable: true,
      workspaceAvailable: true,
      workspaces: [
        { id: 'workspace-1', name: 'agent-platform', root: '/local/agent-platform', gitRepository: true },
        { id: 'workspace-2', name: 'assistant', root: '/local/assistant' }
      ],
      currentWorkspace: { id: 'workspace-1', name: 'agent-platform', root: '/local/agent-platform', gitRepository: true },
      gitBranches: [
        { name: 'main', current: true },
        { name: 'feature/composer', current: false }
      ],
      workspaceFiles: [
        { name: 'ReadMe.md', relativePath: 'ReadMe.md', language: 'markdown' },
        { name: 'AgentApplication.java', relativePath: 'src/main/java/AgentApplication.java', language: 'java' },
        { name: 'AgentFactory.java', relativePath: 'src/main/java/agent/AgentFactory.java', language: 'java' }
      ],
      ...overrides
    },
    global: { stubs: { Teleport: true } }
  })
}

describe('CommandDeck composer controls', () => {
  it('shows the default model and emits attachment upload', async () => {
    const wrapper = mountDeck()
    expect(wrapper.get('.model-trigger').text()).toContain('deepseek-v4-pro')
    await wrapper.get('.attach-button').trigger('click')
    expect(wrapper.emitted('upload')).toHaveLength(1)
  })

  it('offers an accessible remove action for each uploaded attachment', async () => {
    const attachment = { name: 'brief.md', relativePath: 'attachments/session-1/brief.md' }
    const wrapper = mountDeck({ attachments: [attachment] })

    const remove = wrapper.get('.attachment-remove')
    expect(remove.attributes('aria-label')).toContain('brief.md')
    await remove.trigger('click')

    expect(wrapper.emitted('remove-attachment')?.[0]).toEqual([attachment])
  })

  it('changes approval mode from the custom permission menu', async () => {
    const wrapper = mountDeck()
    await wrapper.get('.permission-trigger').trigger('click')
    const options = wrapper.findAll('.permission-menu > button')
    await options[0].trigger('click')
    expect(wrapper.emitted('update:approvalMode')?.[0]).toEqual(['REQUEST_APPROVAL'])
  })

  it('opens the custom Git branch menu and requests a branch switch', async () => {
    const wrapper = mountDeck()

    expect(wrapper.get('.branch-trigger').text()).toContain('main')
    await wrapper.get('.branch-trigger').trigger('click')
    expect(wrapper.emitted('refresh-branches')).toHaveLength(1)
    expect(wrapper.get('.branch-menu').attributes('role')).toBe('listbox')

    const options = wrapper.findAll('.branch-option')
    expect(options).toHaveLength(2)
    await options[1].trigger('click')
    expect(wrapper.emitted('select-branch')?.[0]).toEqual(['feature/composer'])
  })

  it('selects a configured provider and model for the current task', async () => {
    const wrapper = mountDeck()
    await wrapper.get('.model-trigger').trigger('click')
    const options = wrapper.findAll('.model-option-list [role="option"]')
    expect(options).toHaveLength(2)
    await options[1].trigger('click')
    expect(wrapper.emitted('update:selectedModelKey')?.[0]).toEqual(['model-custom'])
  })

  it('prompts for a model instead of submitting when none is selected', async () => {
    const wrapper = mountDeck({ currentModel: null, selectedModelKey: '' })

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('run')).toBeUndefined()
    expect(wrapper.get('.model-selection-error').text()).toContain('Choose an enabled model')
    expect(wrapper.get('.model-menu').exists()).toBe(true)
  })

  it('opens the unified provider and route management page', async () => {
    const wrapper = mountDeck()
    await wrapper.get('.model-trigger').trigger('click')
    await wrapper.get('.model-menu footer button').trigger('click')
    expect(wrapper.emitted('manage-models')).toHaveLength(1)
  })

  it('selects a recent task folder from the composer footer', async () => {
    const wrapper = mountDeck({ currentWorkspace: null })
    await wrapper.get('.workspace-context-trigger').trigger('click')
    const recent = wrapper.findAll('.composer-workspace-menu > button')
    await recent[0].trigger('click')

    expect(wrapper.emitted('select-workspace')?.[0]).toEqual(['workspace-1'])
  })

  it('opens the native folder picker from the custom workspace menu', async () => {
    const wrapper = mountDeck({ currentWorkspace: null })
    await wrapper.get('.workspace-context-trigger').trigger('click')
    await wrapper.get('.composer-workspace-menu footer button').trigger('click')

    expect(wrapper.emitted('pick-workspace')).toHaveLength(1)
  })

  it('renders and filters project files immediately after typing @', async () => {
    const wrapper = mountDeck({ prompt: '' })
    const input = wrapper.get('#promptInput')

    await input.setValue('请解释 @')
    expect(wrapper.get('.file-mention-menu').attributes('role')).toBe('listbox')
    expect(wrapper.findAll('.file-mention-menu [role="option"]')).toHaveLength(3)

    await input.setValue('请解释 @AgentF')
    const options = wrapper.findAll('.file-mention-menu [role="option"]')
    expect(options).toHaveLength(1)
    expect(options[0].text()).toContain('AgentFactory.java')
  })

  it('selects an @ file with the keyboard without submitting the task', async () => {
    const wrapper = mountDeck({ prompt: '' })
    const input = wrapper.get('#promptInput')

    await input.setValue('分析 @AgentApp')
    await wrapper.setProps({ prompt: '分析 @AgentApp' })
    await input.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('run')).toBeUndefined()
    expect(wrapper.emitted('update:prompt')?.at(-1)).toEqual([
      '分析 @src/main/java/AgentApplication.java '
    ])
    expect(wrapper.find('.file-mention-menu').exists()).toBe(false)
  })
})
