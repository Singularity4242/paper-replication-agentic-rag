import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: loadEnv(mode, process.cwd(), '').JAVA_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true,
        timeout: 300_000,
        proxyTimeout: 300_000,
      },
    },
  },
  build: { chunkSizeWarningLimit: 900 },
}));
