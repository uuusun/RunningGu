// 합성 POI 생성기 — SPEC §8.1 폴백 체인의 ③ synth 단계.
// 난수 없이 인덱스 기반(황금각) 결정적 생성 → 같은 입력이면 항상 같은 출력.
// 원본 sampleData.js 의 synthPOIs 와 동일하다(포팅 대상).

const SYNTH_NAMES = {
  food:     ['로컬 맛집', '향토 식당', '제철 밥상', '노포 국밥', '시장 먹거리', '한정식집', '분식 골목', '해물 식당'],
  cafe:     ['로스터리 카페', '뷰 카페', '한옥 카페', '베이커리 카페', '디저트 카페', '브런치 카페'],
  tour:     ['전망대', '랜드마크 광장', '명소 거리', '관광 정원', '포토 스폿', '야경 명소', '테마 거리', '전통 마을'],
  wellness: ['온천 스파', '찜질방', '사우나', '힐링 스파', '족욕 카페'],
  nature:   ['둘레길', '호수 공원', '수목원', '강변 산책로', '숲길', '해안 산책로'],
  history:  ['향토 박물관', '유적지', '문화재 거리', '고택', '서원'],
  lodging:  ['시내 호텔', '리조트', '게스트하우스', '비즈니스 호텔', '펜션'],
}

export function synthPOIs(catKey, center, count = 8) {
  const names = SYNTH_NAMES[catKey] || SYNTH_NAMES.tour
  const out = []
  for (let i = 0; i < count; i++) {
    const a = i * 2.39996            // 황금각(rad)
    const r = 0.004 + (i % 5) * 0.003 // ±0.018도 ≈ ±2km
    out.push({
      name: `${names[i % names.length]} ${i + 1}`,
      lat: center.lat + Math.cos(a) * r,
      lng: center.lng + Math.sin(a) * r,
      desc: '추천 장소(샘플)',
      addr: '',
      url: '',
    })
  }
  return out
}

// 픽스처용 POI 공급자 — 항상 synth(결정적). 실제 앱은 live→sample→synth 폴백(SPEC §8.1).
export const synthProvider = {
  async searchPOIs({ cat, center, count }) {
    return { source: 'synth', places: synthPOIs(cat.key, center, count) }
  },
}
