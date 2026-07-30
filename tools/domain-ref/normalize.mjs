// 원천 → 표준 Race 정규화 + 접수상태 재계산 — SPEC §5.5 · §6.2.
// 원본 normalize.js 대비 변경점: regStatusOf 의 today 를 필수 인자로 (픽스처 결정성 + 테스트 용이).
import { eventsFromRace } from './events.mjs'

// 접수 상태를 '오늘'(Asia/Seoul) 기준으로 재계산 — 크롤 스냅샷은 stale.
export function regStatusOf(race, today) {
  if (!race) return '미정'
  if (!today) throw new Error('regStatusOf: today(YYYY-MM-DD) 필수')
  const { regStart, regEnd } = race
  if (regEnd && today > regEnd) return '마감'
  if (regStart && today < regStart) return '접수전'
  if (regStart || regEnd) return '접수중'
  return race.regStatus || '미정'
}

// 원천(snake_case) / 기존 샘플(camelCase) 양쪽을 흡수하는 어댑터.
export function normalizeRace(raw) {
  if (!raw) return null
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

// 날짜·좌표가 온전한 대회만 통과.
export function isValidRace(r) {
  return !!(r && r.id && r.name && /^\d{4}-\d{2}-\d{2}$/.test(r.date || '') &&
    Number.isFinite(r.lat) && Number.isFinite(r.lng))
}

// 대회명 정규화 — '제8회'·연도·기호 제거로 소스 간 표기차 흡수.
function normName(s) {
  return String(s || '').toLowerCase()
    .replace(/제?\s*\d+\s*회/g, '')
    .replace(/20\d\d년?/g, '')
    .replace(/[^0-9a-z가-힣]/g, '')
}

const STATUS_RANK = { 접수중: 4, 접수전: 3, 미정: 2, 마감: 1 }
const completeness = (r) =>
  (r.imageUrl ? 1 : 0) + (r.officialUrl ? 1 : 0) + (r.venue ? 1 : 0) + (r.startTime ? 1 : 0)

function preferRace(a, b) {
  const ra = STATUS_RANK[a.regStatus] || 0
  const rb = STATUS_RANK[b.regStatus] || 0
  if (ra !== rb) return ra > rb ? a : b
  return completeness(a) >= completeness(b) ? a : b
}

// (정규화 대회명 + 날짜)로 소스 간 중복 병합.
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
  return dedupeRaces((list || []).map(normalizeRace).filter(isValidRace))
}
