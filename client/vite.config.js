import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The API is same-origin (Spring Boot serves the built files), so the client only ever uses relative /api paths.
// In `npm run dev`, /api is proxied to the Spring Boot server named by BV_DEV_API (dev-only, never shipped).
export default defineConfig(() => {
  const target = process.env.BV_DEV_API;
  return {
    plugins: [react()],
    build: { outDir: 'dist', assetsDir: 'assets', sourcemap: false },
    server: target ? { proxy: { '/api': { target, changeOrigin: false } } } : {},
  };
});
