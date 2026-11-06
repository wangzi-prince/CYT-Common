import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import { fileURLToPath, URL } from 'node:url';
import { createRequire } from 'node:module';
import { existsSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';

const elementComponents = join(
  dirname(createRequire(import.meta.url).resolve('element-plus/package.json')),
  'es/components',
);
const elementStyles = readdirSync(elementComponents)
  .filter((name) => existsSync(join(elementComponents, name, 'style/css.mjs')))
  .map((name) => `element-plus/es/components/${name}/style/css`);

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
    // Component auto-imports are injected after Vite's initial dependency scan.
    // Prepare their deep entries up front so visiting a new page cannot restart the optimizer.
    optimizeDeps: {
      include: [
        'element-plus',
        'element-plus/es',
        'element-plus/es/locale/lang/zh-cn',
        ...elementStyles,
      ],
    },
    server: { port: 8190, strictPort: true, proxy },
    preview: { port: 8190, strictPort: true },
    build: { chunkSizeWarningLimit: 1100 },
  };
});
