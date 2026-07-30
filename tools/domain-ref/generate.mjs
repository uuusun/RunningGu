// 골든 픽스처 생성기 — 실데이터(races.json 271행 · durunubi_courses.json 261코스)로
// 도메인 로직을 돌려 기대값 JSON을 뽑는다. Kotlin domain 단위 테스트가 이 픽스처를 assert 한다.
//
// 실행: node tools/domain-ref/generate.mjs
// 출력: tools/domain-ref/fixtures/*.json
//
// 전부 결정적(난수·현재시각 미사용)이라 재실행 시 동일 산출물이어야 한다.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { RECOVERY, PATTERNS, targetKmFor } from './constants.mjs'
import { patternRange, dateRange, offLabel, shortKo, diffDays } from './dates.mjs'
import { stdEvent, stdEventKm, stdEvents } from './events.mjs'
import { normalizeRaces, regStatusOf } from './normalize.mjs'
import { loadCourses, browseCourses, buildRouteNear, courseRegions, filterWalkSpots } from './courses.mjs'
import { buildItinerary } from './engineV2.mjs'
import { synthProvider } from './poiSynth.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = join(HERE, '..', '..')
const DATA = join(REPO, 'reference-web', 'src', 'data')
const OUT = join(HERE, 'fixtures')

const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'))
const write = (name, obj) => {
  writeFileSync(join(OUT, name), JSON.stringify(obj, null, 2) + '\n', 'utf8')
  console.log(`  ✓ fixtures/${name}`)
}

// 좌표열 결정적 체크섬 — 폴리라인 전체를 픽스처에 넣지 않고 동일성만 검증한다.
function coordHash(points) {
  let h = 2166136261 >>> 0
  for (const [la, ln] of points) {
    const s = `${la.toFixed(6)},${ln.toFixed(6)};`
    for (let i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i)
      h = Math.imul(h, 16777619) >>> 0
    }
  }
  return h.toString(16).padStart(8, '0')
}

const round = (n, d = 6) => (Number.isFinite(n) ? Number(n.toFixed(d)) : n)

mkdirSync(OUT, { recursive: true })

// 픽스처 기준 '오늘' — 고정값. 실제 앱은 Asia/Seoul 오늘(SPEC §5.5).
const TODAY = '2026-08-01'

console.log('▶ 원천 로드')
const rawRaces = readJson(join(DATA, 'races.json'))
const rawCourses = readJson(join(DATA, 'durunubi_courses.json'))
const races = normalizeRaces(rawRaces)
const courses = loadCourses(rawCourses)
console.log(`  races ${rawRaces.length} → 정규화·병합 ${races.length}`)
console.log(`  courses ${courses.length}`)

// ────────────────────────────────────────────────────────────
// 1. 정규화 · 종목 표준화 · 접수상태
// ────────────────────────────────────────────────────────────
console.log('▶ normalize / events / regStatus')
write('normalize.json', {
  today: TODAY,
  rawCount: rawRaces.length,
  normalizedCount: races.length,
  // 종목 조합별 분포 — 표준화가 4종으로 수렴하는지 확인.
  eventTypeHistogram: races.reduce((acc, r) => {
    const k = r.eventTypes.join('+') || '(none)'
    acc[k] = (acc[k] || 0) + 1
    return acc
  }, {}),
  regStatusHistogram: races.reduce((acc, r) => {
    const k = regStatusOf(r, TODAY)
    acc[k] = (acc[k] || 0) + 1
    return acc
  }, {}),
  // 대회 전체를 id 순으로 — 필드별 정규화 결과를 통째로 고정.
  races: races
    .slice()
    .sort((a, b) => String(a.id).localeCompare(String(b.id)))
    .map((r) => ({ ...r, lat: round(r.lat), lng: round(r.lng), regStatusToday: regStatusOf(r, TODAY) })),
})

write('events.json', {
  stdEvent: ['풀코스', 'full', '42.195km', '하프', 'half', '21K', '10km', '10K', '단독10', '5km', '건강달리기', '']
    .map((raw) => ({ raw, out: stdEvent(raw) })),
  stdEventKm: [0, 5, 8.9, 9, 10, 17.9, 18, 21.0975, 31.9, 32, 42.195, NaN]
    .map((km) => ({ km: Number.isFinite(km) ? km : null, out: stdEventKm(km) })),
  stdEvents: [
    ['5km', '10km', '하프'],
    ['half', 'full'],
    ['10K', '10km', '10k'],
    [],
  ].map((list) => ({ list, out: stdEvents(list) })),
})

// ────────────────────────────────────────────────────────────
// 2. 날짜 · 패턴
// ────────────────────────────────────────────────────────────
console.log('▶ dates / patterns')
write('dates.json', {
  offLabel: [-2, -1, 0, 1, 2].map((o) => ({ off: o, out: offLabel(o) })),
  shortKo: ['2026-08-22', '2026-01-01', '2026-12-31', '2028-02-29'].map((d) => ({ date: d, out: shortKo(d) })),
  diffDays: [['2026-08-21', '2026-08-23'], ['2026-08-22', '2026-08-22'], ['2026-12-31', '2027-01-01']]
    .map(([a, b]) => ({ a, b, out: diffDays(a, b) })),
  dateRange: [['2026-08-21', '2026-08-23'], ['2026-08-22', '2026-08-22']]
    .map(([s, e]) => ({ start: s, end: e, out: dateRange(s, e) })),
  patternRange: PATTERNS.map((p) => ({
    key: p.key, offsets: p.offsets, raceDate: '2026-08-22', out: patternRange('2026-08-22', p.offsets),
  })),
})

// ────────────────────────────────────────────────────────────
// 3. 동선 엔진 v2 — 종목 4종 × 패턴 4종 (실대회 기준)
// ────────────────────────────────────────────────────────────
console.log('▶ buildItinerary (SPEC §5.6 v2)')

// 종목별 대표 대회 — eventTypes 에 해당 종목이 있고 좌표·시각이 온전한 것 중 id 순 첫 건.
const pickRace = (ev) =>
  races
    .filter((r) => r.eventTypes.includes(ev) && r.startTime)
    .sort((a, b) => String(a.id).localeCompare(String(b.id)))[0]

const EVENTS = ['5K', '10K', '하프', '풀']
const STAY = { name: '테스트 호텔', lat: 36.4800, lng: 127.2900, addr: '세종특별자치시 한누리대로 2000' }

const itinCases = []
for (const event of EVENTS) {
  const race = pickRace(event)
  if (!race) { console.log(`  ⚠ ${event}: 대표 대회 없음 — 건너뜀`); continue }
  for (const p of PATTERNS) {
    const { start, end } = patternRange(race.date, p.offsets)
    const themes = event === '하프' ? ['history', 'wellness'] : ['tour', 'food']
    const res = await buildItinerary(
      { race, stay: STAY, event, themes, start, end },
      synthProvider,
    )
    itinCases.push({
      caseId: `${event}-${p.key}`,
      input: {
        raceId: race.id, raceName: race.name, raceDate: race.date, startTime: race.startTime,
        raceLat: round(race.lat), raceLng: round(race.lng),
        event, pattern: p.key, themes, start, end, stay: STAY,
      },
      expected: {
        sources: res.sources,
        recovery: res.recovery,
        // S7 → S8 연계 목표 거리(SPEC §5.1).
        s8TargetKm: targetKmFor(event),
        dayCount: res.days.length,
        blockCount: res.days.reduce((n, d) => n + d.blocks.length, 0),
        // 장소 중복 없음 검증용.
        distinctPlaceNames: [...new Set(res.days.flatMap((d) => d.blocks.map((b) => b.place?.name).filter(Boolean)))].length,
        placeSlots: res.days.reduce((n, d) => n + d.blocks.filter((b) => b.place).length, 0),
        days: res.days.map((d) => ({
          date: d.date, off: d.off, label: d.label, dateLabel: d.dateLabel, note: d.note,
          blocks: d.blocks.map((b) => ({
            id: b.id, time: b.time, title: b.title, catKey: b.catKey, desc: b.desc,
            place: b.place ? { name: b.place.name, lat: round(b.place.lat), lng: round(b.place.lng), addr: b.place.addr, desc: b.place.desc } : null,
          })),
        })),
      },
    })
  }
}
write('itinerary.json', {
  spec: '§5.6 v2 — 산책 블록 제거(부록 C-8). engine.js 원본과 다름.',
  recoveryRules: RECOVERY,
  poiSource: 'synth (결정적)',
  cases: itinCases,
})

// v2 회귀 가드 — 어떤 케이스에도 산책 계열 블록이 없어야 한다.
const strayWalk = itinCases.flatMap((c) =>
  c.expected.days.flatMap((d) => d.blocks.filter((b) => b.catKey === 'walk' || /산책/.test(b.title)).map((b) => `${c.caseId}/${b.title}`)))
if (strayWalk.length) throw new Error(`v2 위반 — 산책 블록 잔존: ${strayWalk.join(', ')}`)
console.log(`  ✓ 산책 블록 0건 (v2 준수) · 케이스 ${itinCases.length}`)

// ────────────────────────────────────────────────────────────
// 4. 러닝코스 빌더 — 브라우징 · 왕복 경로
// ────────────────────────────────────────────────────────────
console.log('▶ courses / buildRouteNear (SPEC §5.8)')

write('courses-browse.json', {
  total: courses.length,
  regions: courseRegions(courses),
  levelHistogram: courses.reduce((a, c) => { a[c.levelLabel] = (a[c.levelLabel] || 0) + 1; return a }, {}),
  filters: [
    { region: '부산', minKm: 0, maxKm: 999, level: undefined },
    { region: '서울', minKm: 0, maxKm: 5, level: undefined },
    { region: undefined, minKm: 0, maxKm: 3, level: '쉬움' },
    { region: '제주', minKm: 10, maxKm: 999, level: undefined },
  ].map((f) => ({
    filter: f,
    count: browseCourses(courses, f).length,
    firstIds: browseCourses(courses, f).slice(0, 5).map((c) => ({ id: c.id, name: c.name, distKm: c.distKm, levelLabel: c.levelLabel })),
  })),
})

// 실제 출발지 — 두루누비 코스가 지나는 지역 위주.
const STARTS = [
  { label: '부산 해운대',   lat: 35.1587, lng: 129.1604 },
  { label: '서울 여의도',   lat: 37.5265, lng: 126.9340 },
  { label: '경주 첨성대',   lat: 35.8347, lng: 129.2191 },
  { label: '제주 용두암',   lat: 33.5153, lng: 126.5122 },
  { label: '좌표 없음',     lat: NaN,     lng: NaN },
]
const routeCases = []
for (const s of STARTS) {
  for (const targetKm of [3, 5, 10]) {
    const out = buildRouteNear(courses, { lat: s.lat, lng: s.lng, targetKm, radiusKm: 8, limit: 12 })
    routeCases.push({
      caseId: `${s.label}-${targetKm}km`,
      input: { lat: Number.isFinite(s.lat) ? s.lat : null, lng: Number.isFinite(s.lng) ? s.lng : null, targetKm, radiusKm: 8, limit: 12 },
      expected: {
        count: out.length,
        // accessM 오름차순 정렬 확인용.
        accessOrder: out.map((r) => r.accessM),
        results: out.map((r) => ({
          id: r.id, parentName: r.parentName, sido: r.sido, sigun: r.sigun, levelLabel: r.levelLabel,
          fullDistKm: r.fullDistKm, accessM: r.accessM, routeKm: r.routeKm, minutes: r.minutes,
          shortfall: r.shortfall,
          start: [round(r.start[0]), round(r.start[1])],
          pointCount: r.routePoints.length,
          // 왕복이므로 시작점 == 끝점.
          closesLoop: r.routePoints[0][0] === r.routePoints[r.routePoints.length - 1][0] &&
                      r.routePoints[0][1] === r.routePoints[r.routePoints.length - 1][1],
          routeHash: coordHash(r.routePoints),
        })),
      },
    })
  }
}
write('courses-route.json', {
  spec: '§5.8 — 왕복 경로. minutes = routeM/110, shortfall = routeM < targetKm*1000-300',
  cases: routeCases,
})

// 왕복 폐곡선 가드.
const notClosed = routeCases.flatMap((c) => c.expected.results.filter((r) => !r.closesLoop).map((r) => `${c.caseId}/${r.id}`))
if (notClosed.length) throw new Error(`왕복 경로가 닫히지 않음: ${notClosed.slice(0, 5).join(', ')}`)
console.log(`  ✓ 왕복 폐곡선 전건 통과 · 케이스 ${routeCases.length}`)

// ────────────────────────────────────────────────────────────
// 5. 걷기 스팟 필터 (SPEC §5.9) — 카카오 응답 형태 고정 입력
// ────────────────────────────────────────────────────────────
console.log('▶ filterWalkSpots (SPEC §5.9)')
const walkInput = [
  { place_name: '해운대해수욕장', category_name: '여행 > 관광,명소 > 해수욕장', address_name: '부산 해운대구 우동', road_address_name: '부산 해운대구 해운대해변로', x: '129.1603', y: '35.1587', distance: '120', place_url: 'http://place.map.kakao.com/1' },
  { place_name: '해운대해수욕장 공영주차장', category_name: '교통,수송 > 주차장', address_name: '부산 해운대구 우동', road_address_name: '', x: '129.1610', y: '35.1590', distance: '150', place_url: '' },
  { place_name: '동백섬 산책로', category_name: '여행 > 관광,명소 > 산책로', address_name: '부산 해운대구 우동', road_address_name: '부산 해운대구 동백로', x: '129.1520', y: '35.1530', distance: '900', place_url: 'http://place.map.kakao.com/2' },
  { place_name: '해운대 공중화장실', category_name: '여행 > 관광,명소 > 해수욕장', address_name: '부산 해운대구 우동', road_address_name: '', x: '129.1605', y: '35.1585', distance: '130', place_url: '' },
  { place_name: '동백섬 산책로', category_name: '여행 > 관광,명소 > 산책로', address_name: '부산 해운대구 우동', road_address_name: '부산 해운대구 동백로', x: '129.1520', y: '35.1530', distance: '900', place_url: '' },
  { place_name: '스타벅스 해운대', category_name: '음식점 > 카페 > 커피전문점 > 스타벅스', address_name: '부산 해운대구 우동', road_address_name: '', x: '129.1600', y: '35.1600', distance: '200', place_url: '' },
  { place_name: '민락수변공원', category_name: '여행 > 관광,명소 > 공원', address_name: '부산 수영구 민락동', road_address_name: '부산 수영구 민락로', x: '129.1290', y: '35.1530', distance: '2800', place_url: '' },
  { place_name: '해운대 테니스장', category_name: '스포츠,레저 > 테니스장', address_name: '부산 해운대구 우동', road_address_name: '', x: '129.1570', y: '35.1650', distance: '700', place_url: '' },
]
write('walk-spots.json', {
  spec: '§5.9 — 포함 WALK_CAT · 제외 NON_WALK · 이름+주소 중복 제거 · 거리순 12곳',
  input: walkInput,
  expected: filterWalkSpots(walkInput, 12),
})

console.log('\n✅ 픽스처 생성 완료 — tools/domain-ref/fixtures/')
