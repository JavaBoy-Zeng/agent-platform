import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AppSelect from './AppSelect.vue'

describe('AppSelect keyboard behavior', () => {
  it('opens, navigates and selects with the keyboard', async () => {
    const wrapper = mount(AppSelect, {
      props: {
        modelValue: '',
        options: [{ value: 'one', label: 'One' }, { value: 'two', label: 'Two' }]
      }
    })
    const trigger = wrapper.get('[role="combobox"]')
    await trigger.trigger('keydown', { key: 'ArrowDown' })
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(2)
    await trigger.trigger('keydown', { key: 'ArrowDown' })
    await trigger.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['two'])
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
  })

  it('closes on Escape and restores focus', async () => {
    const wrapper = mount(AppSelect, { attachTo: document.body, props: { options: ['one'] } })
    const trigger = wrapper.get('[role="combobox"]')
    await trigger.trigger('keydown', { key: 'Enter' })
    await trigger.trigger('keydown', { key: 'Escape' })
    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })
})
