// 동선 생성 엔진 — SPEC §5.6 v2 확정판.
//
// ⚠ reference-web/src/lib/runninggu/engine.js 와 의도적으로 다르다.
//   원본에는 산책 블록('가벼운 저녁 산책'·'숙소 주변 저녁 산책'·'아침 산책')과
//   nature 산책 풀(pools.walk)이 남아 있으나, 2026-07-16 회의 결정(부록 C-8)으로 제거됐다.
//   산책·러닝은 S8 러닝코스가 담당하며, S7 결과에는 S8 연계 카드만 둔다.
//   → Kotlin ItineraryEngine 은 원본이 아니라 이 파일을 기준으로 포팅한다.
//
// UI·지도·네트워크 비의존 순수 모듈. POI 조달은 searchPOIs 주입으로 분리한다.
import { RECOVERY, CATS, DEFAULT_THEMES, THEME_FALLBACK } from './constants.mjs'
import { dateRange, diffDays, offLabel, shortKo } from './dates.mjs'

// 대회 출발 블록용 의사 POI.
const venuePlace = (race) => ({
  name: race.venue || race.name,
  lat: race.lat,
  lng: race.lng,
  addr: race.region || '',
  url: race.officialUrl || '',
  desc: '대회장',
})

/**
 * 동선 초안 생성.
 * @param {{race, stay, event, themes, start, end}} plan
 * @param {{searchPOIs: (a:{cat,center,raceId,count})=>Promise<{source,places}>}} deps
 * @returns {Promise<{days, sources, recovery, plan}>}
 */
export async function buildItinerary(plan, { searchPOIs }) {
  const { race, stay, event, themes, start, end } = plan
  const rule = RECOVERY[event] || RECOVERY['5K']
  const themeKeys = (themes && themes.length ? themes : DEFAULT_THEMES).slice()

  // 블록 id — 호출 단위 카운터(픽스처·테스트 결정성). 편집 중에는 기존 id를 유지한다(SPEC §5.7).
  let seq = 0
  const newBlockId = () => `blk_${++seq}`

  // ── 1) 카테고리 풀 결정: {food, tour} ∪ themes, noHard면 wellness, 아니면 cafe ──
  const poolKeys = new Set(['food', 'tour', ...themeKeys])
  if (rule.noHard) poolKeys.add('wellness')
  else poolKeys.add('cafe')

  // ── 2) POI 적재 — 전부 대회장 중심 8건. (v2: 숙소 기준 산책 풀 제거) ──
  const venue = { lat: race.lat, lng: race.lng }
  const pools = {}
  const sources = {}
  for (const key of poolKeys) {
    const cat = CATS.find((c) => c.key === key)
    if (!cat) continue
    const { source, places } = await searchPOIs({ cat, center: venue, raceId: race.id, count: 8 })
    pools[key] = places
    sources[key] = source
  }

  // ── 3) 중복 없이 pick (일정 전체에서 같은 장소가 두 번 나오지 않는다) ──
  const used = new Set()
  const pick = (key) => {
    const arr = pools[key] || []
    for (const p of arr) {
      if (!used.has(p.name)) { used.add(p.name); return p }
    }
    return arr[0] || null
  }
  // 테마 우선 선택: [...themes, tour, nature, cafe, history] 중 미사용 POI가 남은 첫 카테고리.
  const pickTheme = () => {
    for (const k of [...themeKeys, ...THEME_FALLBACK]) {
      if (pools[k] && pools[k].some((p) => !used.has(p.name))) return { key: k, place: pick(k) }
    }
    return { key: 'tour', place: pick('tour') }
  }

  const stayPlace = stay && stay.name
    ? { name: stay.name, lat: Number(stay.lat), lng: Number(stay.lng), addr: stay.addr || '', url: '', desc: '숙소' }
    : null

  // ── 4) 일자별 블록 ──
  const days = dateRange(start, end).map((date) => {
    const off = diffDays(race.date, date)
    const blocks = []
    const add = (time, title, catKey, place, desc) =>
      blocks.push({ id: newBlockId(), time, title, catKey, place: place || null, desc: desc || (place ? place.desc : '') })

    let note = ''
    if (off < 0) {
      // D-1 — 체크인 → 카보로딩 저녁. (v2: 저녁 산책 제거)
      add('15:00', '숙소 체크인', 'lodging', stayPlace, stayPlace ? (stayPlace.addr || '여장 풀기') : '여장 풀기')
      add('18:30', '카보로딩 저녁', 'food', pick('food'), '탄수화물 보충 · 무리 없는 메뉴')
      note = '내일 완주 · 가볍게 먹고 푹 쉬기'
    } else if (off === 0) {
      // D-day — 스타트 → 회복 분기. (v2: 숙소 주변 저녁 산책 제거)
      add(race.startTime || '08:00', `🏁 ${race.name} 스타트`, 'race', venuePlace(race), `${event} 완주 · 결승 후 샤워`)
      if (rule.noHard) {
        add('11:00', '온천·회복', 'wellness', pick('wellness'), '완주 근육 회복')
        if (event === '하프') {
          const t = pick('tour')
          add('14:30', '가벼운 관광', 'tour', t, t ? t.desc : '평지 위주 가벼운 코스')
        }
        add('18:00', '회복 저녁', 'food', pick('food'), '소화 잘 되는 회복식')
      } else {
        const t = pickTheme()
        add('13:00', '오후 자유 관광', t.key, t.place, t.place ? t.place.desc : '')
        add('15:30', '카페 한 잔', 'cafe', pick('cafe'), '완주 후 휴식')
        add('18:30', '맛집 저녁', 'food', pick('food'), '오늘은 잘 먹는 날')
      }
      note = rule.dday
    } else {
      // D+N — (v2: 아침 산책 제거)
      if (rule.noHard) add('10:00', '온천·족욕', 'wellness', pick('wellness'), '고강도 제외 · 회복 위주')
      else {
        const t = pick('tour')
        add('10:00', '오전 관광', 'tour', t, t ? t.desc : '')
      }
      add('12:30', '로컬 점심', 'food', pick('food'), '그 지역 별미')
      const t = pickTheme()
      add('14:30', '오후 관광', t.key, t.place, t.place ? t.place.desc : '')
      if (date === end) add('17:00', '체크아웃·귀가', 'lodging', stayPlace, '여행 마무리')
      note = rule.dplus
    }

    return { date, off, label: offLabel(off), dateLabel: shortKo(date), note, blocks }
  })

  return { days, sources, recovery: recoveryBadge(event, days), plan: { ...plan } }
}

// 회복 배지 — noHard 종목만. D+ 있으면 'D+n 회복 모드', 없으면 'D-day 회복 모드'.
export function recoveryBadge(event, days) {
  const rule = RECOVERY[event] || RECOVERY['5K']
  if (!rule.noHard) return null
  const plus = days.find((d) => d.off > 0)
  if (plus) return { label: `${plus.label} 회복 모드`, text: rule.dplus, intensity: rule.intensity }
  return { label: 'D-day 회복 모드', text: rule.dday, intensity: rule.intensity }
}
