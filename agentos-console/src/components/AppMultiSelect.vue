<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  options: { type: Array, default: () => [] },
  valueKey: { type: String, default: 'value' },
  labelKey: { type: String, default: 'label' },
  descriptionKey: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  searchPlaceholder: { type: String, default: '' },
  emptyText: { type: String, default: '' },
  removeLabel: { type: String, default: 'Remove' },
  ariaLabel: { type: String, default: '' },
  allowDuplicates: { type: Boolean, default: false },
  showOrder: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'change'])
const root = ref(null)
const input = ref(null)
const optionElements = ref([])
const open = ref(false)
const query = ref('')
const activeIndex = ref(-1)
const instanceId = useId()
const listboxId = `${instanceId}-listbox`

function optionValue(option) {
  return option && typeof option === 'object' ? option[props.valueKey] : option
}

function optionLabel(option) {
  const value = option && typeof option === 'object' ? option[props.labelKey] : option
  return value == null ? '' : String(value)
}

function optionDescription(option) {
  if (!props.descriptionKey || !option || typeof option !== 'object') return ''
  return option[props.descriptionKey] == null ? '' : String(option[props.descriptionKey])
}

function findOption(value) {
  return props.options.find(option => optionValue(option) === value)
}

const selectedEntries = computed(() => props.modelValue.map((value, index) => {
  const option = findOption(value)
  return { value, index, label: option ? optionLabel(option) : String(value) }
}))

const filteredOptions = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return props.options
  return props.options.filter(option => `${optionLabel(option)} ${optionDescription(option)}`.toLowerCase().includes(needle))
})

const activeOptionId = computed(() => open.value && activeIndex.value >= 0 ? `${instanceId}-option-${activeIndex.value}` : undefined)

function selectedCount(option) {
  const value = optionValue(option)
  return props.modelValue.filter(item => item === value).length
}

function updateValue(next) {
  emit('update:modelValue', next)
  emit('change', next)
}

function setActive(index) {
  if (!filteredOptions.value.length) {
    activeIndex.value = -1
    return
  }
  activeIndex.value = (index + filteredOptions.value.length) % filteredOptions.value.length
  nextTick(() => optionElements.value[activeIndex.value]?.scrollIntoView({ block: 'nearest' }))
}

function openMenu() {
  if (props.disabled) return
  open.value = true
  if (activeIndex.value < 0) setActive(0)
}

function closeMenu() {
  open.value = false
  query.value = ''
  activeIndex.value = -1
}

function focusInput() {
  if (props.disabled) return
  input.value?.focus()
  openMenu()
}

function toggleOption(option) {
  const value = optionValue(option)
  if (props.allowDuplicates) updateValue([...props.modelValue, value])
  else if (props.modelValue.includes(value)) updateValue(props.modelValue.filter(item => item !== value))
  else updateValue([...props.modelValue, value])
  query.value = ''
  nextTick(() => {
    input.value?.focus()
    setActive(filteredOptions.value.findIndex(item => optionValue(item) === value))
  })
}

function removeAt(index) {
  updateValue(props.modelValue.filter((_, itemIndex) => itemIndex !== index))
  nextTick(() => input.value?.focus())
}

function handleKeydown(event) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    if (!open.value) openMenu()
    else setActive(activeIndex.value + 1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) openMenu()
    else setActive(activeIndex.value - 1)
  } else if (event.key === 'Home' && open.value) {
    event.preventDefault()
    setActive(0)
  } else if (event.key === 'End' && open.value) {
    event.preventDefault()
    setActive(filteredOptions.value.length - 1)
  } else if (event.key === 'Enter' && open.value && activeIndex.value >= 0) {
    event.preventDefault()
    toggleOption(filteredOptions.value[activeIndex.value])
  } else if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    closeMenu()
  } else if (event.key === 'Backspace' && !query.value && props.modelValue.length) {
    removeAt(props.modelValue.length - 1)
  } else if (event.key === 'Tab') {
    closeMenu()
  }
}

function closeOnOutsideClick(event) {
  if (open.value && !root.value?.contains(event.target)) closeMenu()
}

watch(filteredOptions, () => setActive(filteredOptions.value.length ? 0 : -1))
watch(() => props.disabled, disabled => { if (disabled) closeMenu() })

onMounted(() => document.addEventListener('pointerdown', closeOnOutsideClick))
onUnmounted(() => document.removeEventListener('pointerdown', closeOnOutsideClick))
</script>

<template>
  <div ref="root" class="app-multi-select" :class="{ 'is-open': open, 'is-disabled': disabled }">
    <div class="app-multi-select__control" @click="focusInput">
      <span v-for="entry in selectedEntries" :key="`${entry.index}-${entry.value}`" class="app-multi-select__chip">
        <b v-if="showOrder">{{ entry.index + 1 }}</b>
        <span>{{ entry.label }}</span>
        <button type="button" :aria-label="`${removeLabel} ${entry.label}`" @click.stop="removeAt(entry.index)">×</button>
      </span>
      <input
        ref="input"
        v-model="query"
        role="combobox"
        aria-autocomplete="list"
        aria-haspopup="listbox"
        :aria-expanded="open"
        :aria-controls="listboxId"
        :aria-activedescendant="activeOptionId"
        :aria-label="ariaLabel"
        :placeholder="modelValue.length ? searchPlaceholder : placeholder"
        :disabled="disabled"
        @focus="openMenu"
        @keydown="handleKeydown"
      />
      <svg aria-hidden="true" viewBox="0 0 24 24"><path d="m8 10 4 4 4-4" /></svg>
    </div>

    <div v-if="open" :id="listboxId" class="app-multi-select__options" role="listbox" aria-multiselectable="true" :aria-label="ariaLabel">
      <div class="app-multi-select__search-note">
        <svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/></svg>
        <span>{{ searchPlaceholder }}</span>
      </div>
      <div
        v-for="(option, index) in filteredOptions"
        :id="`${instanceId}-option-${index}`"
        :key="optionValue(option)"
        :ref="element => { if (element) optionElements[index] = element }"
        class="app-multi-select__option"
        :class="{ 'is-active': index === activeIndex }"
        role="option"
        :aria-selected="selectedCount(option) > 0"
        @pointerdown.prevent
        @pointerenter="activeIndex = index"
        @click="toggleOption(option)"
      >
        <span class="app-multi-select__check"><svg v-if="selectedCount(option)" aria-hidden="true" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg></span>
        <span class="app-multi-select__option-copy"><strong>{{ optionLabel(option) }}</strong><small v-if="optionDescription(option)">{{ optionDescription(option) }}</small></span>
        <b v-if="selectedCount(option)" class="app-multi-select__count">{{ allowDuplicates ? `×${selectedCount(option)}` : 'SELECTED' }}</b>
      </div>
      <p v-if="!filteredOptions.length" class="app-multi-select__empty">{{ emptyText }}</p>
    </div>
  </div>
</template>

<style scoped>
.app-multi-select { position: relative; width: 100%; }
.app-multi-select__control { display: flex; width: 100%; min-height: 38px; flex-wrap: wrap; align-items: center; gap: 5px; padding: 5px 30px 5px 6px; border: 1px solid var(--line); border-radius: 5px; background: #ffffff; cursor: text; transition: border-color 140ms ease, box-shadow 140ms ease; }
.app-multi-select.is-open .app-multi-select__control { border-color: rgba(16, 163, 127, .48); box-shadow: 0 0 0 3px rgba(16, 163, 127, .08); }
.app-multi-select.is-disabled .app-multi-select__control { color: #8a8a8a; background: #f2f2f3; cursor: default; }
.app-multi-select__control > input { min-width: 80px; height: 26px; flex: 1; padding: 0 3px; border: 0; outline: 0; color: #111111; background: transparent; font-family: var(--mono); font-size: 9px; }
.app-multi-select__control > input::placeholder { color: #777777; }
.app-multi-select__control > svg { position: absolute; top: 12px; right: 9px; width: 14px; height: 14px; fill: none; stroke: #555555; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; transition: transform 140ms ease; }
.app-multi-select.is-open .app-multi-select__control > svg { transform: rotate(180deg); }
.app-multi-select__chip { display: inline-flex; max-width: 100%; height: 26px; align-items: center; gap: 5px; padding: 0 3px 0 7px; border: 1px solid rgba(16, 163, 127, .2); border-radius: 4px; color: #0b6f58; background: rgba(16, 163, 127, .08); font-family: var(--mono); font-size: 8px; }
.app-multi-select__chip > b { display: grid; width: 15px; height: 15px; place-items: center; border-radius: 3px; color: #ffffff; background: #178c70; font-size: 7px; font-weight: 500; }
.app-multi-select__chip > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.app-multi-select__chip > button { display: grid; width: 19px; height: 19px; padding: 0; border: 0; border-radius: 3px; place-items: center; color: #347c69; background: transparent; font-size: 13px; cursor: pointer; }
.app-multi-select__chip > button:hover { color: #ffffff; background: #178c70; }
.app-multi-select__options { position: absolute; z-index: 70; top: calc(100% + 5px); left: 0; display: grid; width: 100%; max-height: 285px; gap: 3px; padding: 6px; overflow: auto; border: 1px solid var(--line); border-radius: 8px; background: #ffffff; box-shadow: 0 18px 55px rgba(0, 0, 0, .16); animation: multi-select-in 120ms ease both; }
.app-multi-select__search-note { display: flex; align-items: center; gap: 7px; padding: 6px 8px 8px; border-bottom: 1px solid var(--line-soft); color: #6b6b6b; font-size: 8px; }
.app-multi-select__search-note svg { width: 13px; height: 13px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-width: 1.7; }
.app-multi-select__option { display: grid; min-height: 47px; grid-template-columns: 18px minmax(0, 1fr) auto; gap: 9px; align-items: center; padding: 7px 9px; border-radius: 5px; cursor: pointer; }
.app-multi-select__option.is-active { background: #eeeeef; }
.app-multi-select__option[aria-selected="true"] { background: rgba(16, 163, 127, .07); }
.app-multi-select__check { display: grid; width: 16px; height: 16px; border: 1px solid #c2c2c5; border-radius: 3px; place-items: center; color: #ffffff; background: #ffffff; }
.app-multi-select__option[aria-selected="true"] .app-multi-select__check { border-color: #178c70; background: #178c70; }
.app-multi-select__check svg { width: 12px; height: 12px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 2.2; }
.app-multi-select__option-copy { display: grid; min-width: 0; gap: 3px; }
.app-multi-select__option-copy strong { overflow: hidden; color: #1a1a1a; font-family: var(--mono); font-size: 9px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.app-multi-select__option-copy small { display: -webkit-box; overflow: hidden; color: #626262; font-size: 8px; line-height: 1.35; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.app-multi-select__count { color: #0b7a5f; font-family: var(--mono); font-size: 7px; font-weight: 500; }
.app-multi-select__empty { margin: 12px 8px; color: #6b6b6b; font-size: 9px; }
@keyframes multi-select-in { from { opacity: 0; transform: translateY(-4px) scale(.99); } to { opacity: 1; transform: none; } }
@media (prefers-reduced-motion: reduce) { .app-multi-select__options { animation: none; } .app-multi-select__control > svg { transition: none; } }
</style>
