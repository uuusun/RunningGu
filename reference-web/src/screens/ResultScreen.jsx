import { useEffect, useRef, useState, useMemo } from 'react'
import { useApp } from '../store/appState.jsx'
import { AppBar, CatTag, RecoveryBadge, SourceBadge } from '../components/chrome.jsx'
import Icon from '../components/Icon.jsx'
import MapView from '../map/MapView.jsx'
import {
  dayPins, countPlaces, removeBlock, moveBlock, addBlock, replacePlace,
  searchPOIs, CATS, LODGING_CAT, CAT_LABEL,
} from '../lib/runninggu/index.js'

export default function ResultScreen() {
  const { state, dispatch, back, runEngine, toast } = useApp()
  const { days, activeDay, recovery, editMode, race, stay } = state
  const day = days[activeDay]
  const [activeId, setActiveId] = useState(null)
  const scrollRef = useRef(null)
  const cardRefs = useRef({})

  const pins = useMemo(() => (day ? dayPins(day) : []), [day])
  const accent = day && day.off > 0 ? 'var(--c-orange)' : 'var(--c-primary)'

  // 날짜 바뀌면 첫 핀을 활성으로
  useEffect(() => { setActiveId(pins[0]?.id || null) }, [activeDay, days])

  // 스크롤 동기화 — 화면 중앙 밴드에 들어온 카드의 장소로 지도 이동/핀 강조
  useEffect(() => {
    if (editMode) return
    const root = scrollRef.current
    if (!root) return
    const io = new IntersectionObserver(
      (entries) => {
        const vis = entries.filter((e) => e.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)
        if (vis[0]) setActiveId(vis[0].target.dataset.bid)
      },
      { root, rootMargin: '-45% 0px -45% 0px', threshold: [0, 0.5, 1] }
    )
    Object.values(cardRefs.current).forEach((el) => el && io.observe(el))
    return () => io.disconnect()
  }, [day, editMode])

  const onPin = (id) => {
    setActiveId(id)
    const el = cardRefs.current[id]
    if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }

  if (!day) return (
    <>
      <AppBar onBack={back} title="추천 동선" />
      <div className="empty"><div className="e-title">동선이 아직 없어요.</div><button className="e-link" onClick={() => runEngine()}>다시 추천받기</button></div>
    </>
  )

  const walkBlocks = days.flatMap((d) => d.blocks.filter((b) => b.catKey === 'walk' && b.place).map((b) => ({ ...b, dayLabel: d.label })))

  return (
    <>
      {/* 지도 */}
      <div style={{ position: 'relative', flex: 'none' }}>
        <MapView pins={pins} activeId={activeId} onPinClick={onPin} accent={accent} height={300} />
        <div className="map-overlay-tl">
          <button className="iconbtn iconbtn-float" onClick={back} aria-label="뒤로"><Icon name="back" size={24} stroke={2.2} /></button>
        </div>
        <div className="map-overlay-tr">
          <button className="iconbtn iconbtn-float" onClick={() => toast('동선을 공유했어요 (링크 복사)')} aria-label="공유"><Icon name="share" size={20} stroke={2} /></button>
        </div>
      </div>

      <div className="scr scr-body" ref={scrollRef}>
        <RecoveryBadge recovery={recovery} />

        {/* 요약 + 일자탭 */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 22px 0' }}>
          <div style={{ fontSize: 19, fontWeight: 800, letterSpacing: '-.3px' }}>{race.region} {tripLenLabel(days)}</div>
          <div style={{ fontSize: 12, color: 'var(--c-ink-4)' }}>{countPlaces(days)}곳</div>
        </div>

        <div className="daytabs scr">
          {days.map((d, i) => (
            <button key={d.date} className={`daytab ${i === activeDay ? 'active' : ''} ${d.off > 0 ? 'recovery-day' : ''}`}
              onClick={() => dispatch({ type: 'ACTIVE_DAY', i })}>
              {d.label} · {d.dateLabel}
            </button>
          ))}
        </div>

        {/* 편집 토글 */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 22px 4px' }}>
          <span className={`tl-daylabel ${day.off > 0 ? 'recovery' : ''}`} style={{ margin: 0 }}>
            {day.label} · {day.dateLabel}{day.off > 0 ? ' · 회복' : day.off === 0 ? ' · D-DAY' : ''}
          </span>
          <button onClick={() => dispatch({ type: 'TOGGLE_EDIT' })} style={{ fontSize: 14, fontWeight: 800, color: 'var(--c-primary)' }}>
            {editMode ? '완료' : '편집'}
          </button>
        </div>
        {day.note && <div className="tl-note" style={{ padding: '0 22px' }}>{day.note}</div>}

        {/* 타임라인 / 편집 리스트 */}
        {editMode
          ? <EditList day={day} dayIndex={activeDay} />
          : <Timeline day={day} activeId={activeId} onTap={onPin} cardRefs={cardRefs} />}

        {/* 숙소 주변 산책 코스 */}
        {!editMode && walkBlocks.length > 0 && (
          <div style={{ padding: '8px 22px 20px' }}>
            <div className="field-label" style={{ marginBottom: 10 }}>숙소 주변 산책 코스</div>
            {walkBlocks.map((b) => (
              <div key={b.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 0', borderBottom: '1px solid var(--c-line-4)' }}>
                <span style={{ flex: 'none', width: 32, height: 32, borderRadius: 9, background: 'var(--cat-nature-bg)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--cat-nature-fg)' }}>
                  <Icon name="walk" size={17} stroke={2} />
                </span>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 14, fontWeight: 700 }}>{b.place.name}</div>
                  <div style={{ fontSize: 12, color: 'var(--c-ink-4)' }}>{b.dayLabel} {b.time} · {b.desc}</div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* CTA: 저장/공유 */}
      <div className="cta-bar">
        <button className="btn btn-ghost" onClick={() => toast('동선을 내보냈어요 (이미지/링크)')} aria-label="내보내기"><Icon name="save" size={24} stroke={2} /></button>
        <button className="btn btn-primary" onClick={() => saveTrip(state, dispatch)}>이 동선 저장하기</button>
      </div>

      {state.sheet && <CandidateSheet />}
    </>
  )
}

function Timeline({ day, activeId, onTap, cardRefs }) {
  return (
    <div className="tl-scroll">
      {day.blocks.map((b, i) => {
        const last = i === day.blocks.length - 1
        const active = b.id === activeId
        return (
          <div className={`tl-row tl-card-tap ${active ? 'is-active' : ''}`} key={b.id}
            ref={(el) => (cardRefs.current[b.id] = el)} data-bid={b.id} onClick={() => onTap(b.id)}>
            <div className="tl-rail">
              <div className="tl-num" style={{ background: catColor(b.catKey) }}>{i + 1}</div>
              {!last && <div className="tl-line" />}
            </div>
            <div className={`tl-card ${active ? 'active' : ''}`}>
              <div className="tl-head">
                <span className="tl-title">{b.title}</span>
                <span className="tl-time">{b.time}</span>
              </div>
              <div className="tl-meta">
                {b.catKey !== 'race' && b.catKey !== 'lodging' && <CatTag catKey={b.catKey} />}
                {b.place && <span className="tl-place">{b.place.name}</span>}
              </div>
              {b.desc && b.desc !== b.place?.name && <div className="tl-place" style={{ marginTop: 3 }}>{b.desc}</div>}
            </div>
          </div>
        )
      })}
    </div>
  )
}

// ── 편집 리스트 (팀 프로토타입 편집 패턴: ☰ 순서 / ↺ 교체 / ✕ 삭제 / + 추가) ──
function EditList({ day, dayIndex }) {
  const { state, dispatch } = useApp()
  const [drag, setDrag] = useState(null)

  const apply = (newDays) => dispatch({ type: 'SET_DAYS', days: newDays })

  const onDrop = (to) => {
    if (drag == null || drag === to) { setDrag(null); return }
    apply(moveBlock(state.days, dayIndex, drag, to))
    setDrag(null)
  }

  return (
    <div className="tl-scroll">
      <div className="infobox" style={{ marginBottom: 12, background: 'var(--c-primary-soft2)' }}>
        <Icon name="grip" size={16} stroke={2} style={{ color: 'var(--c-primary)' }} />
        <span>드래그로 순서 변경 · ↺ 교체 · ✕ 삭제</span>
      </div>
      {day.blocks.map((b, i) => (
        <div key={b.id}
          className={`edit-row ${drag === i ? 'dragging' : ''}`}
          draggable
          onDragStart={() => setDrag(i)}
          onDragOver={(e) => e.preventDefault()}
          onDrop={() => onDrop(i)}
        >
          <span className="grip" aria-hidden><Icon name="grip" size={20} stroke={2} /></span>
          <span className="num" style={{ background: catColor(b.catKey) }}>{i + 1}</span>
          <div className="info">
            <div className="t">{b.title}</div>
            <div className="s">{b.time}{b.place ? ` · ${b.place.name}` : ''} · {CAT_LABEL[b.catKey]}</div>
          </div>
          <div className="ops">
            {b.catKey !== 'race' && (
              <button className="opbtn" aria-label="교체" onClick={() => dispatch({ type: 'OPEN_SHEET', sheet: { dayIndex, blockId: b.id, catKey: b.catKey, mode: 'replace' } })}>
                <Icon name="refresh" size={16} stroke={2.2} style={{ color: 'var(--c-ink-2)' }} />
              </button>
            )}
            <button className="opbtn del" aria-label="삭제" onClick={() => apply(removeBlock(state.days, dayIndex, b.id))}>
              <Icon name="x" size={15} stroke={2.4} style={{ color: '#E5484D' }} />
            </button>
          </div>
        </div>
      ))}
      <button className="add-row" onClick={() => dispatch({ type: 'OPEN_SHEET', sheet: { dayIndex, catKey: 'tour', mode: 'add' } })}>
        <Icon name="plus" size={18} stroke={2.4} style={{ color: 'var(--c-ink-2)' }} />장소 추가
      </button>
    </div>
  )
}

// ── 후보 시트 (교체/추가 공용) ──
function CandidateSheet() {
  const { state, dispatch } = useApp()
  const sheet = state.sheet
  const [cat, setCat] = useState(sheet.catKey || 'tour')
  const [list, setList] = useState([])
  const [source, setSource] = useState(null)
  const [loading, setLoading] = useState(true)

  const center = state.stay && state.stay.lat != null ? { lat: Number(state.stay.lat), lng: Number(state.stay.lng) } : { lat: state.race.lat, lng: state.race.lng }

  useEffect(() => {
    let on = true
    setLoading(true)
    const catObj = CATS.find((c) => c.key === cat) || (cat === 'lodging' ? LODGING_CAT : CATS[0])
    searchPOIs({ cat: catObj, center, raceId: state.race.id, count: 8 }).then((res) => {
      if (!on) return
      setList(res.places); setSource(res.source); setLoading(false)
    })
    return () => { on = false }
  }, [cat])

  const choose = (place) => {
    if (sheet.mode === 'replace') {
      dispatch({ type: 'SET_DAYS', days: replacePlace(state.days, sheet.dayIndex, sheet.blockId, place, cat) })
    } else {
      const block = { time: '13:00', title: place.name, catKey: cat, place, desc: place.desc || '' }
      dispatch({ type: 'SET_DAYS', days: addBlock(state.days, sheet.dayIndex, block) })
    }
    dispatch({ type: 'CLOSE_SHEET' })
  }

  return (
    <>
      <div className="sheet-scrim" onClick={() => dispatch({ type: 'CLOSE_SHEET' })} />
      <div className="sheet">
        <div className="grab" />
        <div className="sheet-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {CAT_LABEL[cat]} {sheet.mode === 'replace' ? '교체' : '추가'} · 인근 {source && <SourceBadge source={source} />}
        </div>
        {sheet.mode === 'add' && (
          <div className="chip-row scr" style={{ flex: 'none', padding: '0 20px 12px' }}>
            {[...CATS, LODGING_CAT].map((c) => (
              <button key={c.key} className={`chip ${cat === c.key ? 'active' : ''}`} onClick={() => setCat(c.key)}>{c.label}</button>
            ))}
          </div>
        )}
        <div className="sheet-search"><Icon name="search" size={18} stroke={2} style={{ color: 'var(--c-ink-5)' }} /><span>장소 검색 · 카카오 로컬</span></div>
        <div className="sheet-list scr">
          {loading && <div className="empty">불러오는 중…</div>}
          {list.map((p, i) => (
            <div className="cand" key={p.name + i}>
              <div className="thumb" />
              <div className="info"><div className="cname">{p.name}</div><div className="cmeta">{CAT_LABEL[cat]} · {p.addr || p.desc}</div></div>
              <button className={`pick ${i === 0 ? 'primary' : ''}`} onClick={() => choose(p)}>선택</button>
            </div>
          ))}
        </div>
      </div>
    </>
  )
}

// ── 헬퍼 ──
function catColor(catKey) {
  if (catKey === 'race') return 'var(--c-primary)'
  if (catKey === 'walk' || catKey === 'nature') return 'var(--cat-nature-fg)'
  if (catKey === 'wellness') return 'var(--cat-wellness-fg)'
  if (catKey === 'food') return 'var(--cat-food-fg)'
  if (catKey === 'recovery') return 'var(--c-orange)'
  return 'var(--c-primary)'
}

function tripLenLabel(days) {
  const n = days.length
  if (n <= 1) return '당일치기'
  return `${n - 1}박 ${n}일`
}

function saveTrip(state, dispatch) {
  const trip = {
    id: `${state.race.id}-${state.start}-${state.end}`,
    race: state.race,
    raceName: state.race.name,
    region: state.race.region,
    event: state.event,
    themes: state.themes,
    start: state.start,
    end: state.end,
    days: state.days,
    recovery: state.recovery,
  }
  dispatch({ type: 'SAVE_TRIP', trip, toast: '내 여행에 저장했어요' })
}
