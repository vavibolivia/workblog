import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 시 /api 는 Spring Boot(8080) 로 프록시. 빌드 산출물은 backend 의 static 으로 복사해 단일 jar 로 배포한다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_API_TARGET ?? 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
