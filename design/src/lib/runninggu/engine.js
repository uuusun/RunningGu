// 동선 생성 엔진 — UI 비종속. "편집 가능한 초안" days[] 를 만든다.
import { RECOVERY, CATS, DEFAULT_THEMES } from './constants.js'
import { searchPOIs } from './poi.js'
import { dateRange, diffDays, offLabel, shortKo } from './dates.js'

// 안정적 block id (편집 시 기존 블록 id 유지, 신규만 새 id).
let _seq = 0
export const newBlockId = () => `blk_${++_seq}`

// 거리 라벨 (산책 블록): place.desc 에 km 있으면 그대로, 없으면 회복 상한 기반.
function walkDesc(place, walkKm) {
  if (place && /km/.test(place.desc || '')) return place.desc
  const d = Math.min(walkKm, 3) + (walkKm <= 3 ? 0 : 0)
  return `${d.toFixed(1)}km 내외 · 쉬움`
}

// race → 대회 출발 블록용 의사 POI.
const venuePlace = (race) => ({ name: race.venue || race.name, lat: race.lat, lng: race.lng, addr: race.region || '', url: race.officialUrl || '', desc: '대회장' })

/**
 * 동선 초안 생성.
 * @param {{race, stay, event, themes, start, end}} plan
 * @returns {Promise<{ days, sources, recovery }>}
 */
export async function buildItinerary(plan) {
  const { race, stay, event, themes, start, end } = plan
  const rule = RECOVERY[event] || RECOVERY['5K']
  const themeKeys = (themes && themes.length ? themes : DEFAULT_THEMES).slice()

  // ── 카테고리 풀 결정: {food, tour, ...themes}, noHard면 wellness 보강, 평이하면 cafe 보강 ──
  const poolKeys = new Set(['food', 'tour', ...themeKeys])
  if (rule.noHard) poolKeys.add('wellness')
  else poolKeys.add('cafe')

  const venue = { lat: race.lat, lng: race.lng }
  const stayCenter = stay && stay.lat != null ? { lat: Number(stay.lat), lng: Number(stay.lng) } : venue

  // ── 풀 적재 (대회장 기준 8개) + 산책 풀(숙소 기준 nature 6개) ──
  const pools = {}
  const sources = {}
  for (const key of poolKeys) {
    const cat = CATS.find((c) => c.key === key)
    if (!cat) continue
    const { source, places } = await searchPOIs({ cat, center: venue, raceId: race.id, count: 8 })
    pools[key] = places
    sources[key] = source
  }
  const natureCat = CATS.find((c) => c.key === 'nature')
  const walkRes = await searchPOIs({ cat: natureCat, center: stayCenter, raceId: race.id, count: 6 })
  pools.walk = walkRes.places
  sources.walk = walkRes.source

  // ── 중복 없이 pick (used 셋) ──
  const used = new Set()
  const pick = (key) => {
    const arr = pools[key] || []
    for (const p of arr) {
      if (!used.has(p.name)) { used.add(p.name); return p }
    }
    return arr[0] || null
  }
  const pickTheme = () => {
    for (const k of [...themeKeys, 'tour', 'nature', 'cafe', 'history']) {
      if (pools[k] && pools[k].some((p) => !used.has(p.name))) return { key: k, place: pick(k) }
    }
    return { key: 'tour', place: pick('tour') }
  }

  const stayPlace = stay && stay.name ? { name: stay.name, lat: Number(stay.lat), lng: Number(stay.lng), addr: stay.addr || '', url: '', desc: '숙소' } : null

  // ── 날짜 루프 ──
  const dates = dateRange(start, end)
  const days = dates.map((date) => {
    const off = diffDays(race.date, date)
    const blocks = []
    const add = (time, title, catKey, place, desc) =>
      blocks.push({ id: newBlockId(), time, title, catKey, place: place || null, desc: desc || (place ? place.desc : '') })

    let note = ''
    if (off < 0) {
      // 전날: 체크인 → 카보로딩 저녁 → 가벼운 산책 → 취침
      add('15:00', '숙소 체크인', 'lodging', stayPlace, stayPlace ? (stayPlace.addr || '여장 풀기') : '여장 풀기')
      add('18:30', '카보로딩 저녁', 'food', pick('food'), '탄수화물 보충 · 무리 없는 메뉴')
      { const w = pick('walk'); add('20:00', '가벼운 저녁 산책', 'walk', w, walkDesc(w, rule.walk)) }
      note = '내일 완주 · 가볍게 먹고 푹 쉬기'
    } else if (off === 0) {
      // 당일: 스타트/완주 → (회복 분기) → 저녁 → 숙소 주변 산책
      add(race.startTime || '08:00', `🏁 ${race.name} 스타트`, 'race', venuePlace(race), `${event} 완주 · 결승 후 샤워`)
      if (rule.noHard) {
        add('11:00', '온천·회복', 'wellness', pick('wellness'), '완주 근육 회복')
        if (event === '하프') { const t = pick('tour'); add('14:30', '가벼운 관광', 'tour', t, t ? t.desc : '평지 위주 가벼운 코스') }
        add('18:00', '회복 저녁', 'food', pick('food'), '소화 잘 되는 회복식')
      } else {
        { const t = pickTheme(); add('13:00', '오후 자유 관광', t.key, t.place, t.place ? t.place.desc : '') }
        add('15:30', '카페 한 잔', 'cafe', pick('cafe'), '완주 후 휴식')
        add('18:30', '맛집 저녁', 'food', pick('food'), '오늘은 잘 먹는 날')
      }
      { const w = pick('walk'); add('20:30', '숙소 주변 저녁 산책', 'walk', w, walkDesc(w, rule.walk)) }
      note = rule.dday
    } else {
      // 다음날: 아침 산책 → (회복/관광) → 점심 → 오후 관광 → (마지막날) 체크아웃
      { const w = pick('walk'); add('08:00', '아침 산책', 'walk', w, walkDesc(w, rule.walk)) }
      if (rule.noHard) add('10:00', '온천·족욕', 'wellness', pick('wellness'), '고강도 제외 · 회복 위주')
      else { const t = pick('tour'); add('10:00', '오전 관광', 'tour', t, t ? t.desc : '') }
      add('12:30', '로컬 점심', 'food', pick('food'), '그 지역 별미')
      { const t = pickTheme(); add('14:30', '오후 관광', t.key, t.place, t.place ? t.place.desc : '') }
      if (date === end) add('17:00', '체크아웃·귀가', 'lodging', stayPlace, '여행 마무리')
      note = rule.dplus
    }

    return { date, off, label: offLabel(off), dateLabel: shortKo(date), note, blocks }
  })

  return { days, sources, recovery: recoveryBadge(event, days), plan: { ...plan } }
}

// 회복 모드 배지 (noHard 종목 + D+ 또는 D-day 존재 시 노출).
export function recoveryBadge(event, days) {
  const rule = RECOVERY[event] || RECOVERY['5K']
  if (!rule.noHard) return null
  const plus = days.find((d) => d.off > 0)
  if (plus) return { label: `${plus.label} 회복 모드`, text: rule.dplus, intensity: rule.intensity }
  return { label: 'D-day 회복 모드', text: rule.dday, intensity: rule.intensity }
}
