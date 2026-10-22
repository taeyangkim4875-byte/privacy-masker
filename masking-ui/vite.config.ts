import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// npm run dev: 화면만 고칠 때. /api 는 로컬에서 실행 중인 Spring Boot(8080)로 넘긴다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
