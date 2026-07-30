// 종목 표준화 — SPEC §5.4. 원본 events.js 와 동일(변경 없음).
export function stdEvent(raw) {
  const s = String(raw || '').toLowerCase().replace(/\s/g, '')
  if (s.includes('풀') || s.includes('full') || s.includes('42')) return '풀'
  if (s.includes('하프') || s.includes('half') || s.includes('21')) return '하프'
  if (s.includes('10k') || s.includes('10km') || /(^|[^0-9])10([^0-9]|$)/.test(s)) return '10K'
  return '5K'
}

// 배열 → 표준 종목 배열(중복 제거, 풀>하프>10K>5K 순서 고정).
export function stdEvents(list) {
  const order = ['풀', '하프', '10K', '5K']
  const set = new Set((list || []).map(stdEvent))
  return order.filter((e) => set.has(e))
}

// 거리(km) → 표준 종목 버킷. ≥32→풀 · ≥18→하프 · ≥9→10K · 그 외 5K.
export function stdEventKm(km) {
  const n = Number(km)
  if (!Number.isFinite(n)) return null
  if (n >= 32) return '풀'
  if (n >= 18) return '하프'
  if (n >= 9) return '10K'
  return '5K'
}

// 크롤러 레코드 → 표준 종목 배열.
//  ① has_* 플래그 → ② distances 버킷 → ③ event_types 토큰.
export function eventsFromRace(raw) {
  const flags = []
  if (raw.has_full || raw.hasFull) flags.push('풀')
  if (raw.has_half || raw.hasHalf) flags.push('하프')
  if (raw.has_10k || raw.has10k) flags.push('10K')
  if (raw.has_5k || raw.has5k) flags.push('5K')
  if (flags.length) return stdEvents(flags)

  const dists = raw.distances || []
  if (dists.length) return stdEvents(dists.map(stdEventKm).filter(Boolean))

  return stdEvents(raw.event_types || raw.eventTypes || [])
}
