import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkspaceContextPicker from './WorkspaceContextPicker.vue'

const workspaces = [
  { id: 'agent-platform', name: 'agent-platform', root: '/developer/java/agent-platform' },
  { id: 'admin-console', name: 'admin-console', root: '/developer/web/admin-console' },
  { id: 'docs', name: 'product-docs', root: '/documents/product' }
]

describe('WorkspaceContextPicker search', () => {
  it('shows an assigned workspace as read-only and removes every change action', () => {
    const wrapper = mount(WorkspaceContextPicker, {
      props: { available: true, workspaces, currentWorkspace: workspaces[0] }
    })

    expect(wrapper.get('.workspace-context-fixed').text()).toContain('agent-platform')
    expect(wrapper.find('.workspace-context-trigger').exists()).toBe(false)
    expect(wrapper.find('.composer-workspace-menu').exists()).toBe(false)
  })

  it('searches every workspace by name or full path', async () => {
    const wrapper = mount(WorkspaceContextPicker, {
      props: { available: true, workspaces }
    })

    await wrapper.get('.workspace-context-trigger').trigger('click')
    expect(wrapper.findAll('.workspace-result')).toHaveLength(3)

    await wrapper.get('.workspace-search input').setValue('web')
    expect(wrapper.findAll('.workspace-result')).toHaveLength(1)
    expect(wrapper.get('.workspace-result').text()).toContain('admin-console')

    await wrapper.get('.workspace-search input').setValue('missing')
    expect(wrapper.findAll('.workspace-result')).toHaveLength(0)
    expect(wrapper.get('.workspace-menu-empty').text()).toMatch(/没有匹配的目录|No matching folders/)
  })

  it('selects a filtered workspace and closes the menu', async () => {
    const wrapper = mount(WorkspaceContextPicker, {
      props: { available: true, workspaces }
    })

    await wrapper.get('.workspace-context-trigger').trigger('click')
    await wrapper.get('.workspace-search input').setValue('product')
    await wrapper.get('.workspace-result').trigger('click')

    expect(wrapper.emitted('select')).toEqual([['docs']])
    expect(wrapper.find('.composer-workspace-menu').exists()).toBe(false)
  })
})
