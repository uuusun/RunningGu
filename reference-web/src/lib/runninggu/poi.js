// POI 어댑터 — 폴백 체인: ① 카카오맵 실시간 → ② 사전수집(raceId별) → ③ 합성 샘플.
// 어떤 소스인지 source('live'|'sample'|'synth')로 반환해 결과 화면에서 배지 표기.
import { PRESAMPLED, synthPOIs } from './sampleData.js'

// autoload=false 로 로드된 카카오 SDK 준비 대기.
let kakaoReady = null
export function ensureKakao(timeout = 4000) {
  if (kakaoReady) return kakaoReady
  kakaoReady = new Promise((resolve) => {
    if (typeof window === 'undefined') return resolve(null) // SSR/Node: 폴백 사용
    const start = Date.now()
    const tick = () => {
      const k = window.kakao
      if (k && k.maps && k.maps.services) return resolve(k)
      if (k && k.maps && k.maps.load) {
        k.maps.load(() => resolve(window.kakao))
        return
      }
      if (Date.now() - start > timeout) return resolve(null)
      setTimeout(tick, 120)
    }
    tick()
  })
  return kakaoReady
}

function fromKakao(r) {
  return {
    name: r.place_name,
    lat: Number(r.y),
    lng: Number(r.x),
    desc: (r.category_name || '').split('>').pop()?.trim() || '',
    addr: r.road_address_name || r.address_name || '',
    url: r.place_url || '',
  }
}

// 카카오 실시간 검색 (카테고리 code 우선, 없으면 kw 키워드).
function kakaoSearch(kakao, cat, center, count) {
  return new Promise((resolve, reject) => {
    const ps = new kakao.maps.services.Places()
    const loc = new kakao.maps.LatLng(center.lat, center.lng)
    const opts = { location: loc, radius: 8000, size: Math.min(count, 15), sort: kakao.maps.services.SortBy.DISTANCE }
    const cb = (data, status) => {
      if (status === kakao.maps.services.Status.OK) resolve(data.map(fromKakao))
      else reject(new Error('kakao:' + status))
    }
    if (cat.code) ps.categorySearch(cat.code, cb, opts)
    else ps.keywordSearch(cat.kw || cat.label, cb, opts)
  })
}

// 단일 카테고리 조회. { source, places } 반환.
export async function searchPOIs({ cat, center, raceId, count = 8 }) {
  const kakao = await ensureKakao()
  if (kakao) {
    try {
      const live = await kakaoSearch(kakao, cat, center, count)
      if (live && live.length) return { source: 'live', places: live.slice(0, count) }
    } catch {
      /* 폴백으로 진행 */
    }
  }
  const pre = PRESAMPLED[raceId] && PRESAMPLED[raceId][cat.key]
  if (pre && pre.length) return { source: 'sample', places: pre.slice(0, count) }
  return { source: 'synth', places: synthPOIs(cat.key, center, count) }
}
