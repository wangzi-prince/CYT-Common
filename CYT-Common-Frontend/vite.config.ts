import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxy = Object.fromEntries(
    ['/admin/api', '/integrations', '/healthz', '/api-reference'].map((prefix) => [
      prefix,
      { target: env.API_PROXY_TARGET || 'http://127.0.0.1:8189', changeOrigin: true },
    ]),
  );
  return {
    plugins: [vue(), Components({ resolvers: [ElementPlusResolver()], dts: false })],
    resolve: { alias: { '~': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: { port: 8190, strictPort: true, proxy },
    preview: { port: 8190, strictPort: true },
    build: { chunkSizeWarningLimit: 1100 },
  };
});
