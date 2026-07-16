import { useEffect, useState } from 'react'
import { useApp } from '../store/appState.jsx'
import Icon from '../components/Icon.jsx'
import { AppBar } from '../components/chrome.jsx'
import { diffDays, shortKo, todayStr, regStatusOf, searchFestivalsNear } from '../lib/runninggu/index.js'

export default function RaceScreen() {
  const { state, back, go } = useApp()
  const r = state.race
  const [festivals, setFestivals] = useState([])
  const [festLoading, setFestLoading] = useState(true)

  useEffect(() => {
    if (!r) return
    let on = true
    setFestLoading(true)
    searchFestivalsNear({ lat: r.lat, lng: r.lng, date: r.date })
      .then((list) => { if (on) { setFestivals(list); setFestLoading(false) } })
      .catch(() => { if (on) { setFestivals([]); setFestLoading(false) } })
    return () => { on = false }
  }, [r?.id])

  if (!r) return null
  const d = diffDays(todayStr(), r.date)
  const dday = d > 0 ? `D-${d}` : d === 0 ? 'D-DAY' : `D+${-d}`
  const status = regStatusOf(r)

  return (
    <>
      <AppBar
        onBack={back}
        right={<>
          <button className="iconbtn" aria-label="공유"><Icon name="share" size={23} stroke={2} /></button>
          <button className="iconbtn" aria-label="저장"><Icon name="heart" size={23} stroke={2} /></button>
        </>}
      />
      <div className="scr scr-body">
        {/* 히어로 */}
        <div className="hero-ink">
          <div className="eyebrow">{r.region} · 마라톤</div>
          <div className="h">{r.name}</div>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 14, marginTop: 18 }}>
            <div className="dday">{dday}</div>
            <div style={{ paddingBottom: 4 }}>
              <div style={{ fontSize: 14, fontWeight: 700 }}>{shortKo(r.date)}</div>
              <div style={{ fontSize: 12, color: '#9DA0A8', marginTop: 2 }}>출발 {r.startTime} · {r.venue}</div>
            </div>
          </div>
          <span style={{ display: 'inline-block', marginTop: 16, padding: '6px 12px', borderRadius: 9, background: status === '접수중' ? 'var(--c-lime)' : '#3A3B40', color: status === '접수중' ? 'var(--c-ink)' : '#B7B9BE', fontSize: 12, fontWeight: 800 }}>
            {status}{r.regEnd ? ` · ~${r.regEnd.slice(5).replace('-', '.')}` : ''}
          </span>
        </div>

        {/* 정보 */}
        <div className="info-rows">
          <div className="info-row"><span className="k">종목</span><span className="v">{r.eventTypes.join(' · ')}</span></div>
          <div className="info-row"><span className="k">접수 기간</span><span className="v">{r.regStart?.slice(5).replace('-', '.')} ~ {r.regEnd?.slice(5).replace('-', '.')}</span></div>
          <div className="info-row"><span className="k">주최</span><span className="v">{r.organizer}</span></div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 0 4px' }}>
            <span style={{ fontSize: 12, color: 'var(--c-ink-6)' }}>출처 {r.source} · 확인 {r.checked?.slice(5).replace('-', '.')}</span>
            <a href={r.officialUrl} target="_blank" rel="noreferrer" style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 13, fontWeight: 700, color: 'var(--c-primary)' }}>
              공식 페이지<Icon name="external" size={14} stroke={2.4} />
            </a>
          </div>
        </div>

        {/* 인근 축제 — TourAPI searchFestival2 (대회일 전후 · 대회장 반경 내). 항상 섹션 노출, 없으면 안내. */}
        <div style={{ padding: '14px 0 24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 22px 12px' }}>
            <div style={{ fontSize: 16, fontWeight: 800 }}>대회 기간 인근 축제</div>
            <span style={{ fontSize: 12, color: 'var(--c-ink-6)' }}>한국관광공사</span>
          </div>
          {festLoading ? (
            <div style={{ padding: '0 22px', fontSize: 13, color: 'var(--c-ink-5)' }}>축제를 찾는 중…</div>
          ) : festivals.length > 0 ? (
            <div className="hscroll scr">
              {festivals.map((f) => (
                <div className="hcard" key={f.contentId}>
                  <div className="img" style={f.image ? { backgroundImage: `url(${f.image})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined} />
                  <div className="ct">{f.title}</div>
                  <div className="cs">{f.dateLabel} · 대회장 {f.distKm.toFixed(1)}km</div>
                </div>
              ))}
            </div>
          ) : (
            <div style={{ margin: '0 22px', padding: '18px 16px', borderRadius: 14, background: 'var(--c-fill)', textAlign: 'center' }}>
              <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink-4)' }}>대회 기간에 열리는 인근 축제가 없어요.</div>
              <div style={{ fontSize: 12, color: 'var(--c-ink-6)', marginTop: 4 }}>대회 전후 여행 동선은 그대로 짜드릴 수 있어요.</div>
            </div>
          )}
        </div>
      </div>

      <div className="cta-bar">
        <button className="btn btn-primary" onClick={() => go('plan')}>이 대회로 동선 만들기</button>
      </div>
    </>
  )
}
