<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number, Boolean], default: '' },
  options: { type: Array, default: () => [] },
  valueKey: { type: String, default: 'value' },
  labelKey: { type: String, default: 'label' },
  descriptionKey: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  emptyText: { type: String, default: '' },
  caption: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  ariaLabel: { type: String, default: '' },
  ariaLabelledby: { type: String, default: '' },
  align: { type: String, default: 'left', validator: value => ['left', 'right'].includes(value) }
})

const emit = defineEmits(['update:modelValue', 'change'])
const root = ref(null)
const trigger = ref(null)
const optionElements = ref([])
const open = ref(false)
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

const selectedIndex = computed(() => props.options.findIndex(option => optionValue(option) === props.modelValue))
const selectedOption = computed(() => props.options[selectedIndex.value])
const displayLabel = computed(() => selectedOption.value ? optionLabel(selectedOption.value) : props.placeholder)
const activeOptionId = computed(() => open.value && activeIndex.value >= 0 ? `${instanceId}-option-${activeIndex.value}` : undefined)

function setActive(index) {
  if (!props.options.length) {
    activeIndex.value = -1
    return
  }
  activeIndex.value = (index + props.options.length) % props.options.length
  nextTick(() => optionElements.value[activeIndex.value]?.scrollIntoView?.({ block: 'nearest' }))
}

function openMenu() {
  if (props.disabled) return
  open.value = true
  setActive(selectedIndex.value >= 0 ? selectedIndex.value : 0)
}

function closeMenu({ restoreFocus = false } = {}) {
  open.value = false
  if (restoreFocus) nextTick(() => trigger.value?.focus())
}

function toggleMenu() {
  if (open.value) closeMenu()
  else openMenu()
}

function selectOption(option) {
  const value = optionValue(option)
  emit('update:modelValue', value)
  emit('change', value)
  closeMenu({ restoreFocus: true })
}

function handleKeydown(event) {
  if (props.disabled) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    if (!open.value) openMenu()
    else setActive(activeIndex.value + 1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) {
      openMenu()
      setActive(props.options.length - 1)
    } else setActive(activeIndex.value - 1)
  } else if (event.key === 'Home' && open.value) {
    event.preventDefault()
    setActive(0)
  } else if (event.key === 'End' && open.value) {
    event.preventDefault()
    setActive(props.options.length - 1)
  } else if ((event.key === 'Enter' || event.key === ' ') && open.value) {
    event.preventDefault()
    if (activeIndex.value >= 0) selectOption(props.options[activeIndex.value])
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    openMenu()
  } else if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    closeMenu({ restoreFocus: true })
  } else if (event.key === 'Tab') {
    closeMenu()
  }
}

function closeOnOutsideClick(event) {
  if (open.value && !root.value?.contains(event.target)) closeMenu()
}

watch(() => props.disabled, disabled => {
  if (disabled) closeMenu()
})
watch(() => props.options, () => {
  if (!props.options.length) activeIndex.value = -1
  else if (open.value) setActive(selectedIndex.value >= 0 ? selectedIndex.value : 0)
})

onMounted(() => document.addEventListener('pointerdown', closeOnOutsideClick))
onUnmounted(() => document.removeEventListener('pointerdown', closeOnOutsideClick))
</script>

<template>
  <div ref="root" class="app-select" :class="[`app-select--${align}`, { 'app-select--open': open, 'app-select--captioned': caption }]">
    <button
      ref="trigger"
      class="app-select__trigger"
      type="button"
      role="combobox"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :aria-controls="listboxId"
      :aria-activedescendant="activeOptionId"
      :aria-label="ariaLabel || undefined"
      :aria-labelledby="ariaLabelledby || undefined"
      :disabled="disabled"
      @click="toggleMenu"
      @keydown="handleKeydown"
    >
      <span class="app-select__copy">
        <small v-if="caption">{{ caption }}</small>
        <strong :class="{ 'is-placeholder': !selectedOption }">{{ displayLabel }}</strong>
      </span>
      <svg aria-hidden="true" viewBox="0 0 24 24"><path d="m8 10 4 4 4-4" /></svg>
    </button>

    <div v-if="open" :id="listboxId" class="app-select__options" role="listbox" :aria-label="ariaLabel || caption || undefined">
      <div
        v-for="(option, index) in options"
        :id="`${instanceId}-option-${index}`"
        :key="optionValue(option)"
        :ref="element => { if (element) optionElements[index] = element }"
        class="app-select__option"
        :class="{ 'is-active': index === activeIndex }"
        role="option"
        :aria-selected="optionValue(option) === modelValue"
        @pointerenter="activeIndex = index"
        @click="selectOption(option)"
      >
        <span>{{ optionLabel(option) }}</span>
        <small v-if="optionDescription(option)">{{ optionDescription(option) }}</small>
        <svg v-if="optionValue(option) === modelValue" aria-hidden="true" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg>
      </div>
      <p v-if="!options.length" class="app-select__empty">{{ emptyText || placeholder }}</p>
    </div>
  </div>
</template>

<style scoped>
.app-select {
  position: relative;
  width: 100%;
  font-family: var(--font);
}

.app-select__trigger {
  display: flex;
  width: 100%;
  min-height: var(--app-select-height, 34px);
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 9px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--text);
  text-align: left;
  background: var(--app-select-trigger-bg, var(--canvas));
  cursor: pointer;
  transition: border-color 140ms ease, background 140ms ease, box-shadow 140ms ease;
}

.app-select__trigger:hover:not(:disabled) { background: var(--app-select-trigger-hover, var(--surface)); }
.app-select--open .app-select__trigger { border-color: var(--muted); box-shadow: 0 0 0 3px var(--line-soft); }
.app-select__trigger:disabled { color: var(--dim); background: var(--surface); cursor: default; }
.app-select__trigger:focus-visible { outline: 2px solid var(--text); outline-offset: 2px; }

.app-select__copy { display: grid; min-width: 0; gap: 2px; }
.app-select__copy strong { overflow: hidden; font-family: var(--mono); font-size: 10px; font-weight: 400; text-overflow: ellipsis; white-space: nowrap; }
.app-select__copy strong.is-placeholder { color: var(--dim); }
.app-select__copy small { color: var(--dim); font-family: var(--mono); font-size: 7px; letter-spacing: .1em; }
.app-select--captioned .app-select__copy strong { font-family: var(--font); font-size: 11px; font-weight: 500; }

.app-select__trigger > svg { width: 16px; height: 16px; flex: 0 0 auto; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; transition: transform 140ms ease; }
.app-select--open .app-select__trigger > svg { transform: rotate(180deg); }

.app-select__options {
  position: absolute;
  z-index: 60;
  top: calc(100% + 5px);
  left: 0;
  display: grid;
  width: max(100%, var(--app-select-menu-width, 100%));
  max-height: 280px;
  gap: 3px;
  padding: 6px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--text);
  background: var(--canvas);
  box-shadow: 0 18px 55px rgba(0, 0, 0, .16);
  animation: app-select-in 120ms ease both;
}

.app-select--right .app-select__options { right: 0; left: auto; }
.app-select__option { position: relative; display: grid; min-height: 36px; align-content: center; gap: 3px; padding: 8px 34px 8px 10px; border-radius: 5px; cursor: pointer; }
.app-select__option.is-active { background: var(--surface); }
.app-select__option[aria-selected="true"] { color: var(--text); background: var(--sidebar-active); }
.app-select__option span { overflow: hidden; font-family: var(--mono); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.app-select__option small { overflow: hidden; color: var(--dim); font-family: var(--mono); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.app-select__option > svg { position: absolute; top: 50%; right: 10px; width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 2; transform: translateY(-50%); }
.app-select__empty { margin: 8px; color: #5f5f5f; font-size: 11px; }

@keyframes app-select-in {
  from { opacity: 0; transform: translateY(-4px) scale(.99); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .app-select__options { animation: none; }
  .app-select__trigger > svg { transition: none; }
}
</style>
