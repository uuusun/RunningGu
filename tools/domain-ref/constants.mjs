// 도메인 상수 — SPEC §5.1~5.3. 값 변경 금지(포팅 시 그대로 옮긴다).

// 종목별 회복 룰. walk = 회복일 권장 도보 상한(km) — v2에서는 산책 블록이 없어
// S7→S8 연계 시 목표 거리 기본값 min(walk, 5)km 로만 쓰인다(SPEC §5.1).
export const RECOVERY = {
  '5K':  { walk: 8, noHard: false, intensity: '거의 정상',        dday: '완주 후 오후부터 자유 관광', dplus: '일반 관광 자유' },
  '10K': { walk: 8, noHard: false, intensity: '낮은 피로',        dday: '완주 후 가벼운 관광·축제',   dplus: '일반 관광' },
  '하프': { walk: 5, noHard: true,  intensity: '중등도 피로',      dday: '완주 후 온천·휴식 권장',     dplus: '온천+짧은 산책(고강도 제외)' },
  '풀':   { walk: 3, noHard: true,  intensity: '고강도 회복 필요', dday: '완주 후 회복 집중, 도보 최소', dplus: '스파·온천 중심, 도보 최소' },
}

// S7 → S8 연계 시 러닝코스 목표 거리 기본값 (SPEC §5.1).
export const targetKmFor = (event) => Math.min((RECOVERY[event] || RECOVERY['5K']).walk, 5)

// 취향 카테고리 → POI 조회 방식. code 있으면 카테고리 검색, 없으면 kw 키워드 검색.
export const CATS = [
  { key: 'tour',     label: '관광지',      code: 'AT4' },
  { key: 'food',     label: '맛집',        code: 'FD6' },
  { key: 'cafe',     label: '카페',        code: 'CE7' },
  { key: 'wellness', label: '힐링·웰니스', kw: '온천 스파 사우나 찜질방' },
  { key: 'nature',   label: '자연·트레킹', kw: '둘레길 공원 산책로 수목원' },
  { key: 'history',  label: '역사·문화',   kw: '박물관 유적지 문화재' },
]

// 숙소는 검색 전용(취향 칩 미노출).
export const LODGING_CAT = { key: 'lodging', label: '숙소', code: 'AD5', kw: '호텔 모텔 게스트하우스 펜션' }

export const DEFAULT_THEMES = ['tour', 'food']

export const CAT_LABEL = {
  ...Object.fromEntries(CATS.map((c) => [c.key, c.label])),
  lodging: '숙소',
  race: '대회',
  recovery: '회복',
}

// 일정 패턴 — 대회일 기준 [start, end] 오프셋(일). custom 은 사용자 지정(SPEC §5.2).
export const PATTERNS = [
  { key: 'pre',    label: '전날부터',   sub: '1박 2일', offsets: [-1, 0] },
  { key: 'post',   label: '대회+다음날', sub: '1박 2일', offsets: [0, 1] },
  { key: 'around', label: '전후로',     sub: '2박 3일', offsets: [-1, 1] },
  { key: 'day',    label: '당일치기',   sub: '당일',    offsets: [0, 0] },
]

// 테마 우선 선택 순서 뒤에 붙는 폴백 체인 (SPEC §5.6-5).
export const THEME_FALLBACK = ['tour', 'nature', 'cafe', 'history']
