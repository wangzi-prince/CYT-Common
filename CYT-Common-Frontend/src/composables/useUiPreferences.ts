import { ref, watch } from 'vue';

export type Density = 'comfortable' | 'compact';
function preference(key: string) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function persist(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Preferences are optional when browser storage is unavailable.
  }
}
const density = ref<Density>(
  preference('cyt_ui_density') === 'compact' ? 'compact' : 'comfortable',
);
const collapsed = ref(preference('cyt_ui_sidebar') === 'collapsed');
watch(
  density,
  (value) => {
    document.documentElement.dataset.density = value;
    persist('cyt_ui_density', value);
  },
  { immediate: true },
);
watch(collapsed, (value) => persist('cyt_ui_sidebar', value ? 'collapsed' : 'expanded'));

export function useUiPreferences() {
  return { density, collapsed };
}
