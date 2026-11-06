import { ref, shallowRef } from 'vue';

type ReadCredential = (
  env: string,
  name: string,
  action: 'view' | 'copy',
) => Promise<{ value: string; source: string }>;
export function useCredentialAccess(
  read: ReadCredential,
  clipboard: (value: string) => Promise<void>,
) {
  const target = shallowRef<{ env: string; name: string }>();
  const value = ref(''),
    source = ref(''),
    loading = ref(false),
    copying = ref('');
  let context = 0,
    viewRequest = 0;
  function reset() {
    context++;
    viewRequest++;
    target.value = undefined;
    value.value = '';
    source.value = '';
    loading.value = false;
    copying.value = '';
  }
  async function view(env: string, name: string) {
    const ticket = ++viewRequest,
      current = context;
    target.value = { env, name };
    value.value = '';
    source.value = '';
    loading.value = true;
    try {
      const result = await read(env, name, 'view');
      if (current !== context || ticket !== viewRequest) return;
      value.value = result.value;
      source.value = result.source;
    } catch {
      if (current === context && ticket === viewRequest) target.value = undefined;
    } finally {
      if (current === context && ticket === viewRequest) loading.value = false;
    }
  }
  async function copy(env: string, name: string) {
    if (copying.value) return 'cancelled';
    const current = context;
    copying.value = name;
    try {
      let result;
      try {
        result = await read(env, name, 'copy');
      } catch {
        return 'failed';
      }
      if (current !== context) return 'cancelled';
      try {
        await clipboard(result.value);
        return 'copied';
      } catch {
        return 'clipboardUnavailable';
      }
    } finally {
      if (current === context) copying.value = '';
    }
  }
  return { target, value, source, loading, copying, reset, view, copy };
}
