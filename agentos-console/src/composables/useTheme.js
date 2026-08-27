import { computed, ref } from 'vue'

const STORAGE_KEY = 'agentos.theme.v1'
const allowed = new Set(['system', 'light', 'dark'])
const selected = ref(readTheme())

function readTheme() {
  try {
    const value = localStorage.getItem(STORAGE_KEY) || 'system'
    return allowed.has(value) ? value : 'system'
  } catch {
    return 'system'
  }
}

function applyTheme(value) {
  document.documentElement.dataset.theme = value
  document.documentElement.style.colorScheme = value === 'system' ? 'light dark' : value
}

applyTheme(selected.value)

export function useTheme() {
  function setTheme(value) {
    if (!allowed.has(value)) return
    selected.value = value
    applyTheme(value)
    try { localStorage.setItem(STORAGE_KEY, value) } catch { /* optional preference */ }
  }
  return {
    theme: computed(() => selected.value),
    setTheme
  }
}
