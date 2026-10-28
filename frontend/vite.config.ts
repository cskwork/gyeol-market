import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { cpSync, createReadStream, existsSync, statSync } from 'node:fs'
import { join, normalize } from 'node:path'
import { fileURLToPath, URL } from 'node:url'

// Seed product images live with the backend (served by Spring Boot at /images).
const backendImagesDir = fileURLToPath(new URL('../src/main/resources/static/images', import.meta.url))

/**
 * Demo builds have no Spring Boot to serve /images, so ship the seed images
 * with the static bundle (build) and serve them from the dev server (dev).
 */
function demoImages(): Plugin {
  let outDir = ''
  return {
    name: 'demo-seed-images',
    configResolved(config) {
      outDir = config.build.outDir
    },
    configureServer(server) {
      server.middlewares.use('/images', (req, res, next) => {
        const file = normalize(join(backendImagesDir, decodeURIComponent((req.url ?? '').split('?')[0])))
        if (!file.startsWith(backendImagesDir) || !existsSync(file) || !statSync(file).isFile()) return next()
        createReadStream(file).pipe(res)
      })
    },
    closeBundle() {
      cpSync(backendImagesDir, join(outDir, 'images'), { recursive: true })
    },
  }
}

// Two build targets:
// - default: storefront served by Spring Boot at /app, built into
//   src/main/resources/static/app; the dev server proxies /api to :8080.
// - VITE_DEMO=1: standalone static demo at /, built into frontend/dist, with an
//   in-browser mock API (src/demo) instead of the backend.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const demo = (process.env.VITE_DEMO ?? env.VITE_DEMO) === '1'

  return {
    base: demo ? '/' : '/app/',
    plugins: [react(), ...(demo ? [demoImages()] : [])],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    build: {
      outDir: demo
        ? fileURLToPath(new URL('./dist', import.meta.url))
        : fileURLToPath(new URL('../src/main/resources/static/app', import.meta.url)),
      emptyOutDir: true,
      // Keep font subsets as separate files: inlining hundreds of small woff2
      // slices as base64 would bloat the render-blocking stylesheet.
      assetsInlineLimit: (file: string) => (/\.woff2?$/.test(file) ? false : undefined),
    },
    server: {
      port: 5173,
      proxy: demo
        ? undefined
        : {
            '/api': { target: 'http://localhost:8080', changeOrigin: true },
            '/images': { target: 'http://localhost:8080', changeOrigin: true },
          },
    },
    test: {
      environment: 'jsdom',
    },
  }
})
