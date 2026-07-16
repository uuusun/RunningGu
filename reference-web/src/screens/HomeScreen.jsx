import { useEffect, useMemo, useState } from 'react'
import { useApp } from '../store/appState.jsx'
import Icon from '../components/Icon.jsx'
import { dow, todayStr, regStatusOf } from '../lib/runninggu/index.js'

const MON_EN = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
const DOW_KO = ['일', '월', '화', '수', '목', '금', '토']
const EVENTS = ['5K', '10K', '하프', '풀']

export default function HomeScreen() {
  const { races, dispatch } = useApp()
  const [view, setView] = useState('list')
  const [region, setRegion] = useState(null)
  const [month, setMonth] = useState(null)
  const [evt, setEvt] = useState(null)
  const [openOnly, setOpenOnly] = useState(false)
  const [q, setQ] = useState('')

  // 다가오는 대회만 노출(오늘 이후). 지난 대회는 목록에서 제외.
  const upcoming = useMemo(() => {
    const today = todayStr()
    return races.filter((r) => r.date >= today)
  }, [races])

  const regions = useMemo(() => [...new Set(upcoming.map((r) => r.region))], [upcoming])
  const months = useMemo(() => [...new Set(upcoming.map((r) => Number(r.date.slice(5, 7))))].sort((a, b) => a - b), [upcoming])

  const filtered = useMemo(() => {
    return upcoming
      .filter((r) => (region ? r.region === region : true))
      .filter((r) => (month ? Number(r.date.slice(5, 7)) === month : true))
      .filter((r) => (evt ? r.eventTypes.includes(evt) : true))
      .filter((r) => (openOnly ? regStatusOf(r) === '접수중' : true))
      .filter((r) => (q ? (r.name + r.venue + r.region).includes(q) : true))
      .sort((a, b) => a.date.localeCompare(b.date))
  }, [upcoming, region, month, evt, openOnly, q])

  const clearAll = () => { setRegion(null); setMonth(null); setEvt(null); setOpenOnly(false); setQ('') }

  return (
    <>
      {/* 헤더 */}
      <div className="appbar" style={{ padding: '6px 22px 14px' }}>
        <span style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <span style={{ width: 30, height: 30, borderRadius: 9, background: 'var(--c-lime)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#15161B" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M3 17l5-6 4 3 5-8" /><circle cx="20" cy="5" r="1.4" fill="#15161B" stroke="none" /></svg>
          </span>
          <span style={{ fontSize: 20, fontWeight: 800, letterSpacing: '-.5px' }}>런트립</span>
        </span>
        <span className="spacer" />
        <button className="iconbtn" aria-label="검색"><Icon name="search" size={22} stroke={2} /></button>
      </div>

      {/* 리스트/캘린더 토글 */}
      <div style={{ padding: '0 22px 14px', flex: 'none' }}>
        <div className="toggle" role="tablist">
          <button className={`opt ${view === 'list' ? 'active' : ''}`} onClick={() => setView('list')}>리스트</button>
          <button className={`opt ${view === 'calendar' ? 'active' : ''}`} onClick={() => setView('calendar')}>캘린더</button>
        </div>
      </div>

      {/* 검색 인풋 */}
      <div style={{ padding: '0 22px 12px', flex: 'none' }}>
        <label className="sheet-search" style={{ margin: 0 }}>
          <Icon name="search" size={18} stroke={2} style={{ color: 'var(--c-ink-5)' }} />
          <input
            value={q} onChange={(e) => setQ(e.target.value)} placeholder="대회·지역 검색"
            style={{ border: 'none', outline: 'none', background: 'transparent', flex: 1, fontSize: 14, color: 'var(--c-ink)', fontFamily: 'inherit' }}
          />
        </label>
      </div>

      {/* 필터 칩 — 접수 가능 → 종목(거리) → 지역 → 월 순 */}
      <div className="chip-row scr" style={{ flex: 'none' }}>
        <button className={`chip ${openOnly ? 'active' : ''}`} onClick={() => setOpenOnly(!openOnly)}>접수 가능만</button>
        {EVENTS.map((e) => (
          <button key={e} className={`chip ${evt === e ? 'active' : ''}`} onClick={() => setEvt(evt === e ? null : e)}>
            {e}{evt === e && <span className="x">✕</span>}
          </button>
        ))}
        {regions.map((rg) => (
          <button key={rg} className={`chip ${region === rg ? 'active' : ''}`} onClick={() => setRegion(region === rg ? null : rg)}>
            {rg}{region === rg && <span className="x">✕</span>}
          </button>
        ))}
        {months.map((m) => (
          <button key={m} className={`chip ${month === m ? 'active' : ''}`} onClick={() => setMonth(month === m ? null : m)}>
            {m}월{month === m && <span className="x">✕</span>}
          </button>
        ))}
      </div>

      {/* 목록 / 캘린더 */}
      {view === 'calendar' ? (
        <CalendarView races={filtered} onSelect={(r) => dispatch({ type: 'SELECT_RACE', race: r })} />
      ) : (
        <div className="scr scr-body" style={{ padding: '0 22px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink-4)' }}>
            대회 <span style={{ color: 'var(--c-primary)' }}>{filtered.length}</span>
          </div>
          {filtered.length === 0 && (
            <div className="empty">
              <div className="e-title">조건에 맞는 대회가 없어요.</div>
              <div>필터를 바꿔보세요.</div>
              <button className="e-link" onClick={clearAll}>필터 초기화</button>
            </div>
          )}
          {filtered.map((r, i) => (
            <RaceCard key={r.id} race={r} featured={i === 0 && regStatusOf(r) === '접수중'} onClick={() => dispatch({ type: 'SELECT_RACE', race: r })} />
          ))}
        </div>
      )}
    </>
  )
}

// ── 월간 캘린더 뷰 ── 대회가 있는 날에 점 표시, 날짜 탭 시 해당일 대회 목록.
function CalendarView({ races, onSelect }) {
  const [cur, setCur] = useState(() => {
    const d = races[0]?.date || todayStr()
    return { y: Number(d.slice(0, 4)), m: Number(d.slice(5, 7)) }
  })
  const [selDay, setSelDay] = useState(null)

  const byDate = useMemo(() => {
    const map = {}
    for (const r of races) (map[r.date] = map[r.date] || []).push(r)
    return map
  }, [races])

  const monthKey = `${cur.y}-${String(cur.m).padStart(2, '0')}`
  const startDow = new Date(cur.y, cur.m - 1, 1).getDay()
  const daysInMonth = new Date(cur.y, cur.m, 0).getDate()
  const today = todayStr()

  // 월 이동 시 선택 초기화
  useEffect(() => { setSelDay(null) }, [monthKey])

  const cells = []
  for (let i = 0; i < startDow; i++) cells.push(null)
  for (let d = 1; d <= daysInMonth; d++) cells.push(d)

  const dstr = (d) => `${monthKey}-${String(d).padStart(2, '0')}`
  const go = (delta) => setCur((c) => {
    const m = c.m + delta
    if (m < 1) return { y: c.y - 1, m: 12 }
    if (m > 12) return { y: c.y + 1, m: 1 }
    return { y: c.y, m }
  })

  // 하단 목록: 선택일이 있으면 그 날, 없으면 이달 전체.
  const listed = selDay
    ? (byDate[selDay] || [])
    : races.filter((r) => r.date.startsWith(monthKey)).sort((a, b) => a.date.localeCompare(b.date))

  return (
    <div className="scr scr-body" style={{ padding: '0 22px 16px' }}>
      {/* 월 네비 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '2px 4px 12px' }}>
        <button className="iconbtn" onClick={() => go(-1)} aria-label="이전 달"><Icon name="back" size={22} stroke={2.2} /></button>
        <div style={{ fontSize: 16, fontWeight: 800 }}>{cur.y}.{cur.m}</div>
        <button className="iconbtn" onClick={() => go(1)} aria-label="다음 달"><Icon name="chevronRight" size={22} stroke={2.2} /></button>
      </div>
      {/* 요일 헤더 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7,1fr)', marginBottom: 4 }}>
        {DOW_KO.map((w, i) => (
          <div key={w} style={{ textAlign: 'center', fontSize: 11, fontWeight: 700, padding: '4px 0', color: i === 0 ? '#E5484D' : i === 6 ? '#2B5CFF' : 'var(--c-ink-5)' }}>{w}</div>
        ))}
      </div>
      {/* 날짜 그리드 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7,1fr)', rowGap: 2 }}>
        {cells.map((d, i) => {
          if (d === null) return <div key={`b${i}`} />
          const ds = dstr(d)
          const list = byDate[ds] || []
          const has = list.length > 0
          const sel = selDay === ds
          const isToday = ds === today
          return (
            <button
              key={ds}
              onClick={() => has && setSelDay(sel ? null : ds)}
              style={{
                height: 46, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 3,
                border: 'none', background: 'transparent', cursor: has ? 'pointer' : 'default', fontFamily: 'inherit',
              }}
            >
              <span style={{
                width: 28, height: 28, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 13, fontWeight: has ? 800 : 500,
                color: sel ? '#fff' : has ? 'var(--c-ink)' : 'var(--c-ink-6)',
                background: sel ? 'var(--c-primary)' : 'transparent',
                border: isToday && !sel ? '1.5px solid var(--c-primary)' : '1.5px solid transparent',
              }}>{d}</span>
              <span style={{ height: 5, display: 'flex', gap: 2 }}>
                {has && !sel && <span style={{ width: 5, height: 5, borderRadius: '50%', background: 'var(--c-primary)' }} />}
                {has && list.length > 1 && !sel && <span style={{ fontSize: 9, lineHeight: '5px', color: 'var(--c-ink-5)', fontWeight: 700 }}>{list.length}</span>}
              </span>
            </button>
          )
        })}
      </div>

      {/* 하단 목록 */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 12 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink-4)' }}>
          {selDay ? `${Number(selDay.slice(5, 7))}.${Number(selDay.slice(8, 10))} (${dow(selDay)})` : `${cur.m}월`} 대회 <span style={{ color: 'var(--c-primary)' }}>{listed.length}</span>
        </div>
        {listed.length === 0 && (
          <div className="empty"><div className="e-title">이 {selDay ? '날' : '달'}엔 대회가 없어요.</div></div>
        )}
        {listed.map((r) => (
          <RaceCard key={r.id} race={r} featured={false} onClick={() => onSelect(r)} />
        ))}
      </div>
    </div>
  )
}

function RaceCard({ race, featured, onClick }) {
  const mon = Number(race.date.slice(5, 7))
  const day = race.date.slice(8, 10)
  const status = regStatusOf(race)
  const closed = status !== '접수중'
  return (
    <button className={`race-card ${featured ? 'featured' : ''} ${closed ? 'closed' : ''}`} onClick={onClick} style={{ textAlign: 'left', width: '100%' }}>
      <div className="datecol">
        <div className={`mon ${featured ? 'hot' : ''}`}>{MON_EN[mon - 1]}</div>
        <div className="day">{day}</div>
        <div className="dow">{dow(race.date)}</div>
      </div>
      <div className="body">
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
          <div className="name">{race.name}</div>
          <Icon name="heart" size={22} stroke={1.5} fill={featured ? 'var(--c-orange)' : 'none'} style={{ color: featured ? 'var(--c-orange)' : '#C2C4C9', flex: 'none' }} />
        </div>
        <div className="place">{race.region} · {race.venue}</div>
        <div className="evt-tags">
          {race.eventTypes.map((e, idx) => <span key={e} className={`evt-tag ${idx === 0 ? 'hi' : ''}`}>{e}</span>)}
        </div>
        <div className="statusline">
          <span className={`statuschip ${closed ? 'closed' : 'open'}`}>{status}</span>
          <span className="source">{race.source} · 확인 {race.checked?.slice(5).replace('-', '.')}</span>
        </div>
      </div>
    </button>
  )
}
