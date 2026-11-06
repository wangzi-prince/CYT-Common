import axios from 'axios';
import { ElMessage } from 'element-plus';

export const TOKEN_KEY = 'cyt_common_admin_token';
const instance = axios.create({
  timeout: 120_000,
  headers: { 'Content-Type': 'application/json' },
});
instance.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token && !config.url?.startsWith('http')) config.headers.Authorization = `Bearer ${token}`;
  return config;
});
instance.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const body = error.response?.data;
      const message =
        body?.error?.message ||
        body?.detail ||
        (error.response
          ? `请求失败（${error.response.status}）`
          : '无法连接服务，请检查后端是否启动');
      ElMessage.error({ message: String(message), grouping: true });
      if (error.response?.status === 401) {
        localStorage.removeItem(TOKEN_KEY);
        if (!window.location.hash.startsWith('#/login')) window.location.hash = '#/login';
      }
    }
    return Promise.reject(error);
  },
);
export async function get<T>(path: string, params?: Record<string, unknown>): Promise<T> {
  return (await instance.get<T>(path, { params })).data;
}
export async function post<T = { ok: boolean }>(path: string, body?: unknown): Promise<T> {
  return (await instance.post<T>(path, body)).data;
}
export async function put<T = { ok: boolean }>(path: string, body: unknown): Promise<T> {
  return (await instance.put<T>(path, body)).data;
}
export async function remove<T = { ok: boolean }>(path: string): Promise<T> {
  return (await instance.delete<T>(path)).data;
}
export async function download(path: string, filename: string, params?: Record<string, unknown>) {
  const { data } = await instance.get(path, { params, responseType: 'blob' });
  const url = URL.createObjectURL(data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
