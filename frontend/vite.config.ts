import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

/**
 * The dev server proxies `/api` to the backend so the browser always talks to a single origin and
 * the backend needs no CORS configuration. The target is deployment configuration, overridable
 * through `GW2_BACKEND_ORIGIN` (mirroring the backend's own `GW2_API_PORT` pattern); the localhost
 * value is only the local-development fallback.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'GW2_')

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
    },
    server: {
      port: Number(env.GW2_FRONTEND_PORT ?? 5173),
      proxy: {
        '/api': {
          target: env.GW2_BACKEND_ORIGIN ?? 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    preview: {
      port: Number(env.GW2_FRONTEND_PORT ?? 5173),
      proxy: {
        '/api': {
          target: env.GW2_BACKEND_ORIGIN ?? 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.spec.ts']
    }
  }
})
