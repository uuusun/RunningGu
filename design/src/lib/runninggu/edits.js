// 동선 편집 연산 — days[]/blocks[] 를 불변(immutable)으로 조작.
// 팀 프로토타입의 수정/삭제/추가/순서·시간 변경 UI가 이 함수들을 호출하면
// 지도 핀·폴리라인·일자탭·스크롤 동기화가 파생값으로 자동 재계산된다.
import { newBlockId } from './engine.js'

const mapDay = (days, dayIndex, fn) =>
  days.map((d, i) => (i === dayIndex ? fn(d) : d))

// 블록 필드 수정(시간/제목/설명 등).
export function updateBlock(days, dayIndex, blockId, patch) {
  return mapDay(days, dayIndex, (d) => ({
    ...d,
    blocks: d.blocks.map((b) => (b.id === blockId ? { ...b, ...patch } : b)),
  }))
}

// 블록 삭제.
export function removeBlock(days, dayIndex, blockId) {
  return mapDay(days, dayIndex, (d) => ({
    ...d,
    blocks: d.blocks.filter((b) => b.id !== blockId),
  }))
}

// 블록 장소 교체(후보 시트에서 선택한 POI로). 안정적 id 유지.
export function replacePlace(days, dayIndex, blockId, place, catKey) {
  return updateBlock(days, dayIndex, blockId, {
    place,
    desc: place.desc || '',
    ...(catKey ? { catKey } : {}),
  })
}

// 블록 추가(맨 끝 또는 index 위치). 새 안정적 id 부여.
export function addBlock(days, dayIndex, block, atIndex) {
  return mapDay(days, dayIndex, (d) => {
    const nb = { id: newBlockId(), time: '', title: '', catKey: 'tour', place: null, desc: '', ...block }
    const arr = d.blocks.slice()
    if (atIndex == null || atIndex >= arr.length) arr.push(nb)
    else arr.splice(Math.max(0, atIndex), 0, nb)
    return { ...d, blocks: arr }
  })
}

// 순서 변경(from → to, 같은 날 안에서).
export function moveBlock(days, dayIndex, from, to) {
  return mapDay(days, dayIndex, (d) => {
    const arr = d.blocks.slice()
    if (from < 0 || from >= arr.length || to < 0 || to >= arr.length) return d
    const [it] = arr.splice(from, 1)
    arr.splice(to, 0, it)
    return { ...d, blocks: arr }
  })
}

// ── 파생값: 한 날(day)의 지도 핀/폴리라인 좌표 ──
// place 좌표가 있는 블록만, 순서대로 번호 부여.
export function dayPins(day) {
  if (!day) return []
  const pins = []
  day.blocks.forEach((b) => {
    if (b.place && Number.isFinite(b.place.lat) && Number.isFinite(b.place.lng)) {
      pins.push({
        n: pins.length + 1,
        id: b.id,
        lat: b.place.lat,
        lng: b.place.lng,
        title: b.place.name || b.title,
        catKey: b.catKey,
      })
    }
  })
  return pins
}

// 동선 전체 장소 수(저장/요약용).
export function countPlaces(days) {
  return days.reduce((n, d) => n + d.blocks.filter((b) => b.place).length, 0)
}
