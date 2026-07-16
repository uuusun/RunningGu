// 날짜 유틸 — 로컬 타임존 자정 기준. 문자열 'YYYY-MM-DD' 입출력.
const DOW = ['일', '월', '화', '수', '목', '금', '토']

export function parseDate(s) {
  const [y, m, d] = String(s).split('-').map(Number)
  return new Date(y, m - 1, d)
}
export function fmtDate(dt) {
  const y = dt.getFullYear()
  const m = String(dt.getMonth() + 1).padStart(2, '0')
  const d = String(dt.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}
// 오늘 자정 기준 'YYYY-MM-DD'.
export function todayStr() {
  return fmtDate(new Date())
}
export function addDays(s, n) {
  const dt = parseDate(s)
  dt.setDate(dt.getDate() + n)
  return fmtDate(dt)
}
// 두 'YYYY-MM-DD' 간 일수 차 (b - a).
export function diffDays(a, b) {
  return Math.round((parseDate(b) - parseDate(a)) / 86400000)
}
export function dow(s) {
  return DOW[parseDate(s).getDay()]
}
// 'MM.DD (요일)'
export function shortKo(s) {
  const dt = parseDate(s)
  return `${String(dt.getMonth() + 1).padStart(2, '0')}.${String(dt.getDate()).padStart(2, '0')} ${DOW[dt.getDay()]}`
}

// off(대회일 기준 오프셋) → 라벨. -1 → 'D-1', 0 → 'D-day', +n → 'D+n'.
export function offLabel(off) {
  if (off === 0) return 'D-day'
  return off < 0 ? `D${off}` : `D+${off}`
}

// 패턴 offsets + 대회일 → { start, end } 'YYYY-MM-DD'.
export function patternRange(raceDate, offsets) {
  return { start: addDays(raceDate, offsets[0]), end: addDays(raceDate, offsets[1]) }
}

// 'MM.DD ~ MM.DD' 범위 라벨.
export function tripRangeLabel(start, end) {
  const f = (s) => s.slice(5).replace('-', '.')
  return `${f(start)} ~ ${f(end)}`
}

// start~end(포함) 날짜 배열.
export function dateRange(start, end) {
  const out = []
  let cur = start
  const n = diffDays(start, end)
  for (let i = 0; i <= n; i++) {
    out.push(cur)
    cur = addDays(cur, 1)
  }
  return out
}
