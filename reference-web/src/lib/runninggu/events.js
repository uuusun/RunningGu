// 종목 표준화 — 원천의 다양한 표기를 ['5K','10K','하프','풀'] 4종으로 정규화.
//  풀/full/42 → '풀', 하프/half/21 → '하프', 10k → '10K', 그 외 → '5K'.
export function stdEvent(raw) {
  const s = String(raw || '').toLowerCase().replace(/\s/g, '')
  if (s.includes('풀') || s.includes('full') || s.includes('42')) return '풀'
  if (s.includes('하프') || s.includes('half') || s.includes('21')) return '하프'
  if (s.includes('10k') || s.includes('10km') || /(^|[^0-9])10([^0-9]|$)/.test(s)) return '10K'
  return '5K'
}

// 배열 → 표준 종목 배열(중복 제거, 풀>하프>10K>5K 순서 유지).
export function stdEvents(list) {
  const order = ['풀', '하프', '10K', '5K']
  const set = new Set((list || []).map(stdEvent))
  return order.filter((e) => set.has(e))
}

// 거리(km) → 4종 표준 종목 버킷. 트레일/비표준 거리(11·15·28·40km 등)를 근접 종목으로 매핑.
//  ≥32→풀, ≥18→하프, ≥9→10K, 그 외→5K.
export function stdEventKm(km) {
  const n = Number(km)
  if (!Number.isFinite(n)) return null
  if (n >= 32) return '풀'
  if (n >= 18) return '하프'
  if (n >= 9) return '10K'
  return '5K'
}

// 크롤러 레코드 → 표준 종목 배열.
//  ① has_* 플래그 우선(데이터 계약), ② 없으면 distances 거리 버킷, ③ 최후로 event_types 토큰.
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
