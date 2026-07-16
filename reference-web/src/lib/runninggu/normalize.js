import { eventsFromRace } from './events.js'
import { todayStr } from './dates.js'

// 접수 상태를 '오늘' 기준으로 재계산 — 크롤링 시점 스냅샷(reg_status)이 stale 하므로.
//  reg_end 지남 → 마감 / reg_start 이전 → 접수전 / 그 사이 → 접수중.
export function regStatusOf(race, today = todayStr()) {
  if (!race) return '미정'
  const { regStart, regEnd } = race
  if (regEnd && today > regEnd) return '마감'
  if (regStart && today < regStart) return '접수전'
  if (regStart || regEnd) return '접수중'
  return race.regStatus || '미정'
}

// 원천 데이터 → 앱 내부 정규화 Race(camelCase).
// 크롤러 원천(snake_case: race_id·event_date·latitude…)과 기존 샘플(camelCase),
// 두 표기를 모두 흡수하는 어댑터 레이어. eventTypes는 has_* 플래그/거리 기준으로 표준화.
export function normalizeRace(raw) {
  if (!raw) return null
  // 여러 후보 키 중 처음으로 정의된 값 채택.
  const pick = (...keys) => {
    for (const k of keys) if (raw[k] !== undefined && raw[k] !== '') return raw[k]
    return undefined
  }
  const num = (v) => {
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }
  return {
    id: pick('id', 'race_id'),
    name: pick('name'),
    region: pick('region'),
    venue: pick('venue', 'road_address'),
    date: pick('date', 'event_date'),
    startTime: pick('startTime', 'start_time'),
    eventTypes: eventsFromRace(raw),
    regStatus: pick('regStatus', 'reg_status'),
    regStart: pick('regStart', 'reg_start'),
    regEnd: pick('regEnd', 'reg_end'),
    organizer: pick('organizer'),
    source: pick('source'),
    checked: pick('checked', 'last_checked'),
    officialUrl: pick('officialUrl', 'official_url'),
    detailUrl: pick('detailUrl', 'detail_url'),
    imageUrl: pick('imageUrl', 'image_url'),
    lat: num(pick('lat', 'latitude')),
    lng: num(pick('lng', 'longitude')),
    category: pick('category'),
  }
}

// 유효 대회만 통과 — 날짜·좌표가 온전해야 목록 정렬/지도/D-day 계산이 안전.
export function isValidRace(r) {
  return !!(r && r.id && r.name && /^\d{4}-\d{2}-\d{2}$/.test(r.date || '') &&
    Number.isFinite(r.lat) && Number.isFinite(r.lng))
}

// 대회명 정규화 — '제8회'·연도·공백/기호 제거로 소스 간 표기차 흡수(백엔드 dedup 규칙과 동일).
function normName(s) {
  return String(s || '').toLowerCase()
    .replace(/제?\s*\d+\s*회/g, '')
    .replace(/20\d\d년?/g, '')
    .replace(/[^0-9a-z가-힣]/g, '')
}

// 접수 상태 우선순위(높을수록 유용) — 동일 대회 여러 소스 중 더 유용한 레코드 선택에 사용.
const STATUS_RANK = { 접수중: 4, 접수전: 3, 미정: 2, 마감: 1 }
function completeness(r) {
  return (r.imageUrl ? 1 : 0) + (r.officialUrl ? 1 : 0) + (r.venue ? 1 : 0) + (r.startTime ? 1 : 0)
}
function preferRace(a, b) {
  const ra = STATUS_RANK[a.regStatus] || 0
  const rb = STATUS_RANK[b.regStatus] || 0
  if (ra !== rb) return ra > rb ? a : b
  return completeness(a) >= completeness(b) ? a : b
}

// (정규화 대회명 + 날짜)로 중복 제거 — 마라톤GO/마라톤온라인 등 소스 간 동일 대회 병합.
export function dedupeRaces(list) {
  const byKey = new Map()
  for (const r of list) {
    const key = `${normName(r.name)}|${r.date}`
    const prev = byKey.get(key)
    byKey.set(key, prev ? preferRace(prev, r) : r)
  }
  return [...byKey.values()]
}

export function normalizeRaces(list) {
  const clean = (list || []).map(normalizeRace).filter(isValidRace)
  return dedupeRaces(clean)
}
