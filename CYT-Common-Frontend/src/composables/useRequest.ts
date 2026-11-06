import { ref, shallowRef } from 'vue';
import { ElMessageBox } from 'element-plus';

export function useRequest<T>(loader: () => Promise<T>) {
  const data = shallowRef<T>();
  const loading = ref(false);
  const error = ref(false);
  let generation = 0;
  async function load() {
    const current = ++generation;
    loading.value = true;
    error.value = false;
    try {
      const value = await loader();
      if (current === generation) data.value = value;
    } catch {
      if (current === generation) {
        data.value = undefined;
        error.value = true;
      }
    } finally {
      if (current === generation) loading.value = false;
    }
  }
  return { data, loading, error, load };
}
export function useAction() {
  const busy = ref(false);
  async function run<T>(task: () => Promise<T>): Promise<T | undefined> {
    if (busy.value) return undefined;
    busy.value = true;
    try {
      return await task();
    } catch {
      return undefined;
    } finally {
      busy.value = false;
    }
  }
  return { busy, run };
}
export async function confirm(message: string, title = '确认操作') {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning',
    });
    return true;
  } catch {
    return false;
  }
}
export function dateTime(value: string | null | undefined) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'short',
        timeStyle: 'medium',
        hour12: false,
      }).format(new Date(value))
    : '—';
}
