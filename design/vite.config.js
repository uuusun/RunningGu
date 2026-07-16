import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// 모바일 우선 단일 서피스 웹앱. lib/runninggu 는 UI 비종속 순수 모듈.
// TourAPI(data.go.kr)·카카오 REST 는 CORS 미지원 → dev 프록시로 우회하며 키를 서버에서 주입.
//   브라우저는 키 없이 /api/kto, /api/kakao 로 호출 → 프록시가 serviceKey/Authorization 추가.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '') // VITE_ 접두사 없는 키까지 로드
  const TOUR = env.TOUR_API_KEY || ''
  const REST = env.KAKAO_REST_KEY || ''

  return {
    plugins: [react()],
    server: {
      host: true,
      // 5173 = 카카오 콘솔 Web 플랫폼에 등록된 도메인. strictPort로 포트 밀림(지도 도메인 불일치) 방지.
      port: 5173,
      strictPort: true,
      proxy: {
        // TourAPI: /api/kto/<Service>/<op>?... → apis.data.go.kr/B551011/... (&serviceKey 주입)
        '/api/kto': {
          target: 'https://apis.data.go.kr/B551011',
          changeOrigin: true,
          secure: true,
          rewrite: (path) => {
            const p = path.replace(/^\/api\/kto/, '')
            const sep = p.includes('?') ? '&' : '?'
            return `${p}${sep}serviceKey=${encodeURIComponent(TOUR)}`
          },
        },
        // 카카오 로컬: /api/kakao/<op>?... → dapi.kakao.com/... (Authorization 헤더 주입)
        '/api/kakao': {
          target: 'https://dapi.kakao.com',
          changeOrigin: true,
          secure: true,
          rewrite: (path) => path.replace(/^\/api\/kakao/, ''),
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyReq) => {
              if (REST) proxyReq.setHeader('Authorization', `KakaoAK ${REST}`)
            })
          },
        },
      },
    },
  }
})
