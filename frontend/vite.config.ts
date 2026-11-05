import react from '@vitejs/plugin-react'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'

// 화면 왼쪽 위 버전 표시: 정본은 backend/pom.xml 의 <version>. 커밋·빌드 시각은 마우스를 올리면 보인다.
const pom = readFileSync(new URL('../backend/pom.xml', import.meta.url), 'utf8')
const APP_VERSION = /<artifactId>workblog-backend<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>/.exec(pom)?.[1] ?? '0.0.0'
let commit = ''
try { commit = execFileSync('git', ['rev-parse', '--short', 'HEAD'], { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim() } catch { /* git 없음 */ }
const pad = (n: number) => String(n).padStart(2, '0')
const d = new Date()
const BUILT = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`

// 개발 시 /api 는 Spring Boot(8080) 로 프록시. 빌드 산출물은 backend 의 static 으로 복사해 단일 jar 로 배포한다.
export default defineConfig({
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify(APP_VERSION),
    __APP_COMMIT__: JSON.stringify(commit),
    __APP_BUILT__: JSON.stringify(BUILT),
  },
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
