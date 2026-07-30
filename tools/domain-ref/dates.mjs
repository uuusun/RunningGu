// 날짜 유틸 — 'YYYY-MM-DD' 입출력.
// 원본 reference-web/src/lib/runninggu/dates.js 대비 변경점:
//   로컬 타임존 Date → UTC 기반 순수 날짜 연산. 타임존 무관하게 결과가 같다(SPEC §6.6 KST 규칙 안전).
//   Kotlin 포팅 시 java.time.LocalDate 로 1:1 대응된다.
const DOW = ['일', '월', '화', '수', '목', '금', '토']

// 'YYYY-MM-DD' → UTC epoch ms (자정).
export function parseDate(s) {
  const [y, m, d] = String(s).split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

export function fmtDate(ms) {
  const dt = new Date(ms)
  const y = dt.getUTCFullYear()
  const m = String(dt.getUTCMonth() + 1).padStart(2, '0')
  const d = String(dt.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

const DAY_MS = 86400000

export function addDays(s, n) {
  return fmtDate(parseDate(s) + n * DAY_MS)
}

// 두 'YYYY-MM-DD' 간 일수 차 (b - a).
export function diffDays(a, b) {
  return Math.round((parseDate(b) - parseDate(a)) / DAY_MS)
}

export function dow(s) {
  return DOW[new Date(parseDate(s)).getUTCDay()]
}

// 'MM.DD 요일'
export function shortKo(s) {
  const dt = new Date(parseDate(s))
  const m = String(dt.getUTCMonth() + 1).padStart(2, '0')
  const d = String(dt.getUTCDate()).padStart(2, '0')
  return `${m}.${d} ${DOW[dt.getUTCDay()]}`
}

// off(대회일 기준 오프셋) → 라벨. -1 → 'D-1', 0 → 'D-day', +n → 'D+n'.
export function offLabel(off) {
  if (off === 0) return 'D-day'
  return off < 0 ? `D${off}` : `D+${off}`
}

// 패턴 offsets + 대회일 → { start, end }.
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
  const n = diffDays(start, end)
  for (let i = 0; i <= n; i++) out.push(addDays(start, i))
  return out
}
