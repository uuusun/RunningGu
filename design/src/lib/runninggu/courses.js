// 두루누비 걷기 코스 — 사전 파싱된 좌표(durunubi_courses.json) 기반.
//  ① 지역/거리/난이도 브라우징(browseCourses)
//  ② 사용자 위치에서 가까운 코스를 원하는 길이로 잘라주는 위치 추천(sliceCoursesNear)
import RAW from '../../data/durunubi_courses.json'

const LEVEL = { 1: '쉬움', 2: '보통', 3: '어려움' }

// 미터 단위 하버사인.
function haversineM(a, b) {
  const R = 6371000
  const toRad = (d) => (d * Math.PI) / 180
  const dLat = toRad(b[0] - a[0])
  const dLng = toRad(b[1] - a[1])
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a[0])) * Math.cos(toRad(b[0])) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(s))
}

// 코스 포인트열 → 누적거리(m) 배열.
function cumDist(points) {
  const cum = [0]
  for (let i = 1; i < points.length; i++) cum[i] = cum[i - 1] + haversineM(points[i - 1], points[i])
  return cum
}

// 정규화된 코스(라벨 부여). points 는 [[lat,lng],...].
export const ALL_COURSES = RAW.map((c) => ({
  ...c,
  levelLabel: LEVEL[c.level] || '보통',
}))

// ── 브라우징 ── 지역(sido)·거리·난이도 필터.
export function browseCourses({ region, minKm = 0, maxKm = 999, level } = {}) {
  return ALL_COURSES
    .filter((c) => (region ? c.sido === region : true))
    .filter((c) => c.distKm >= minKm && c.distKm < maxKm)
    .filter((c) => (level ? c.levelLabel === level : true))
    .sort((a, b) => a.distKm - b.distKm)
}

// 코스에서 (lat,lng)에 가장 가까운 포인트 index + 거리(m).
function nearestPoint(points, lat, lng) {
  let bestI = -1, bestD = Infinity
  for (let i = 0; i < points.length; i++) {
    const d = haversineM([lat, lng], points[i])
    if (d < bestD) { bestD = d; bestI = i }
  }
  return { index: bestI, distM: bestD }
}

// startIdx 중심으로 targetM 길이의 연속 구간 추출(앞으로 우선, 부족하면 뒤로 확장).
function sliceSegment(points, cum, startIdx, targetM) {
  let lo = startIdx, hi = startIdx
  while (hi < points.length - 1 && cum[hi] - cum[startIdx] < targetM) hi++
  while (cum[hi] - cum[lo] < targetM && lo > 0) lo--
  return { points: points.slice(lo, hi + 1), distM: cum[hi] - cum[lo] }
}

// ── 위치 기반 코스 자르기 ──
//  (lat,lng) 반경 radiusKm 안을 지나는 코스를 찾아, 가까운 지점부터 lengthKm 구간을 잘라 반환.
//  가까운 순 정렬. 장거리 트레일도 '내 근처 Lkm'로 축약돼 나온다.
export function sliceCoursesNear({ lat, lng, lengthKm = 5, radiusKm = 5, limit = 20 }) {
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return []
  const targetM = lengthKm * 1000
  const out = []
  for (const c of ALL_COURSES) {
    if (!c.points || c.points.length < 2) continue
    const near = nearestPoint(c.points, lat, lng)
    if (near.distM > radiusKm * 1000) continue
    const cum = cumDist(c.points)
    const seg = sliceSegment(c.points, cum, near.index, targetM)
    out.push({
      id: c.id,
      parentName: c.name,
      sido: c.sido,
      sigun: c.sigun,
      levelLabel: c.levelLabel,
      fullDistKm: c.distKm,
      accessM: Math.round(near.distM),          // 내 위치 → 코스 진입점 거리
      segKm: Math.round((seg.distM / 1000) * 10) / 10, // 잘라낸 구간 길이
      segPoints: seg.points,
    })
  }
  return out.sort((a, b) => a.accessM - b.accessM).slice(0, limit)
}

// ni에서 한쪽 방향으로 targetM 길이의 one-way 구간을 뽑는다. dir=+1 앞으로, -1 뒤로.
// 반환: { points(ni에서 시작하도록 정렬), distM }.
function oneWay(points, cum, ni, targetM, dir) {
  if (dir > 0) {
    let hi = ni
    while (hi < points.length - 1 && cum[hi] - cum[ni] < targetM) hi++
    return { seg: points.slice(ni, hi + 1), distM: cum[hi] - cum[ni] }
  }
  let lo = ni
  while (lo > 0 && cum[ni] - cum[lo] < targetM) lo--
  return { seg: points.slice(lo, ni + 1).reverse(), distM: cum[ni] - cum[lo] }
}

// 출발점 근처 두루누비 코스로 '왕복' 경로 생성 — 목표거리 targetKm에 맞춰 절반만큼 가고 되돌아옴.
//  출발지 복귀 + 정확한 거리. 목표에 가까운 방향을 자동 선택.
export function buildRouteNear({ lat, lng, targetKm = 5, radiusKm = 8, limit = 12 }) {
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return []
  const halfM = (targetKm * 1000) / 2
  const out = []
  for (const c of ALL_COURSES) {
    if (!c.points || c.points.length < 2) continue
    const near = nearestPoint(c.points, lat, lng)
    if (near.distM > radiusKm * 1000) continue
    const cum = cumDist(c.points)
    // 앞/뒤 중 목표 절반에 더 가깝게(길게) 뻗는 방향 선택.
    const fwd = oneWay(c.points, cum, near.index, halfM, +1)
    const bwd = oneWay(c.points, cum, near.index, halfM, -1)
    const pick = fwd.distM >= bwd.distM ? fwd : bwd
    // 왕복: one-way + 되돌아오기(마지막 점 중복 제거).
    const back = pick.seg.slice(0, -1).reverse()
    const routePoints = pick.seg.concat(back)
    const routeM = pick.distM * 2
    out.push({
      id: c.id,
      parentName: c.name,
      sido: c.sido,
      sigun: c.sigun,
      levelLabel: c.levelLabel,
      fullDistKm: c.distKm,
      accessM: Math.round(near.distM),
      routeKm: Math.round((routeM / 1000) * 10) / 10,
      // 분당 약 110m(느긋한 러닝/빠른 걷기) 가정한 예상 시간(분).
      minutes: Math.max(1, Math.round(routeM / 110)),
      shortfall: routeM < targetKm * 1000 - 300, // 코스가 짧아 목표에 못 미침
      start: pick.seg[0],
      routePoints,
    })
  }
  return out.sort((a, b) => a.accessM - b.accessM).slice(0, limit)
}

// sido별 코스 개수(브라우징 칩 정렬용).
export function courseRegions() {
  const cnt = {}
  for (const c of ALL_COURSES) if (c.sido) cnt[c.sido] = (cnt[c.sido] || 0) + 1
  return Object.entries(cnt).sort((a, b) => b[1] - a[1]).map(([r]) => r)
}
