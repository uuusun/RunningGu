// 러닝코스 빌더 — SPEC §5.8. 두루누비 사전 파싱본(261코스) 기반, 외부 API 불필요.
// 원본 courses.js 대비 변경점: RAW 를 import 하지 않고 loadCourses(raw) 로 주입 — 순수 함수화.
// 알고리즘(하버사인·누적거리·최근접·왕복 생성)은 원본과 동일하다.

const LEVEL = { 1: '쉬움', 2: '보통', 3: '어려움' }

// 미터 단위 하버사인.
export function haversineM(a, b) {
  const R = 6371000
  const toRad = (d) => (d * Math.PI) / 180
  const dLat = toRad(b[0] - a[0])
  const dLng = toRad(b[1] - a[1])
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a[0])) * Math.cos(toRad(b[0])) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(s))
}

// 코스 포인트열 → 누적거리(m) 배열.
export function cumDist(points) {
  const cum = [0]
  for (let i = 1; i < points.length; i++) cum[i] = cum[i - 1] + haversineM(points[i - 1], points[i])
  return cum
}

// 원천 배열 → 라벨 부여된 코스 배열.
export function loadCourses(raw) {
  return raw.map((c) => ({ ...c, levelLabel: LEVEL[c.level] || '보통' }))
}

// ── 브라우징 ── 지역(sido)·거리·난이도 필터 + 거리 오름차순.
export function browseCourses(all, { region, minKm = 0, maxKm = 999, level } = {}) {
  return all
    .filter((c) => (region ? c.sido === region : true))
    .filter((c) => c.distKm >= minKm && c.distKm < maxKm)
    .filter((c) => (level ? c.levelLabel === level : true))
    .sort((a, b) => a.distKm - b.distKm)
}

// 코스에서 (lat,lng)에 가장 가까운 포인트 index + 거리(m).
export function nearestPoint(points, lat, lng) {
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

// ── 위치 기반 편도 구간 추출 ──
export function sliceCoursesNear(all, { lat, lng, lengthKm = 5, radiusKm = 5, limit = 20 }) {
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return []
  const targetM = lengthKm * 1000
  const out = []
  for (const c of all) {
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
      accessM: Math.round(near.distM),
      segKm: Math.round((seg.distM / 1000) * 10) / 10,
      segPoints: seg.points,
    })
  }
  return out.sort((a, b) => a.accessM - b.accessM).slice(0, limit)
}

// ni에서 한쪽 방향으로 targetM 길이의 편도 구간. dir=+1 앞으로, -1 뒤로.
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

// ── 왕복 경로 생성 (SPEC §5.8) ──
//  ① 코스별 최근접 진입점(반경 초과 제외) ② 앞/뒤 중 targetKm/2 에 더 길게 뻗는 방향의 편도
//  ③ 편도 + 역방향 복귀 = routePoints ④ accessM 오름차순 limit.
export function buildRouteNear(all, { lat, lng, targetKm = 5, radiusKm = 8, limit = 12 }) {
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return []
  const halfM = (targetKm * 1000) / 2
  const out = []
  for (const c of all) {
    if (!c.points || c.points.length < 2) continue
    const near = nearestPoint(c.points, lat, lng)
    if (near.distM > radiusKm * 1000) continue
    const cum = cumDist(c.points)
    const fwd = oneWay(c.points, cum, near.index, halfM, +1)
    const bwd = oneWay(c.points, cum, near.index, halfM, -1)
    const pick = fwd.distM >= bwd.distM ? fwd : bwd
    const back = pick.seg.slice(0, -1).reverse()   // 마지막 점 중복 제거
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
      minutes: Math.max(1, Math.round(routeM / 110)),   // 분당 110m
      shortfall: routeM < targetKm * 1000 - 300,        // 목표 -300m 미만이면 부족
      start: pick.seg[0],
      routePoints,
    })
  }
  return out.sort((a, b) => a.accessM - b.accessM).slice(0, limit)
}

// sido별 코스 개수 내림차순(브라우징 칩 정렬용).
export function courseRegions(all) {
  const cnt = {}
  for (const c of all) if (c.sido) cnt[c.sido] = (cnt[c.sido] || 0) + 1
  return Object.entries(cnt).sort((a, b) => b[1] - a[1]).map(([r]) => r)
}

// ── 걷기 스팟 필터 (SPEC §5.9) ── 카카오 키워드 응답을 걸러내는 순수 함수부.
export const WALK_CAT = /공원|관광|명소|산책|둘레|하천|유원지|수목원|숲|생태|휴양|호수|해수욕|해변|등산로|트레킹|자연/
export const NON_WALK = /화장실|주차장|주차|테니스|풋살|축구장|야구장|농구장|체육관|관리사무소|매점|안내소|정류장/
export const WALK_QUERIES = ['공원', '산책로', '둘레길', '하천']

// 카카오 documents[] (여러 키워드 결과를 이어붙인 것) → 걷기 스팟 12곳.
export function filterWalkSpots(documents, limit = 12) {
  const seen = new Set()
  const out = []
  for (const d of documents || []) {
    const cat = d.category_name || ''
    if (!WALK_CAT.test(cat) || NON_WALK.test(d.place_name)) continue
    const key = d.place_name + d.address_name
    if (seen.has(key)) continue
    seen.add(key)
    out.push({
      name: d.place_name,
      category: cat.split('>').pop()?.trim() || '',
      addr: d.road_address_name || d.address_name || '',
      lat: Number(d.y), lng: Number(d.x),
      distM: Number(d.distance) || 0,
      url: d.place_url || '',
    })
  }
  return out.sort((a, b) => a.distM - b.distM).slice(0, limit)
}
