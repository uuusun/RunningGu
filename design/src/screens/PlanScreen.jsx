import { useState, useMemo, useEffect } from 'react'
import { useApp } from '../store/appState.jsx'
import { AppBar } from '../components/chrome.jsx'
import { PATTERNS, patternRange, parseDate, fmtDate, diffDays } from '../lib/runninggu/index.js'

export default function PlanScreen() {
  const { state, back, go, set } = useApp()
  const r = state.race
  const [pattern, setPattern] = useState(state.pattern || 'around')
  const [customStart, setCustomStart] = useState(r.date)
  const [customEnd, setCustomEnd] = useState(r.date)
  const [half, setHalf] = useState(false) // 직접 선택: 시작만 찍힌 상태

  const range = useMemo(() => {
    if (pattern === 'custom') return { start: customStart, end: customEnd }
    const p = PATTERNS.find((x) => x.key === pattern)
    return patternRange(r.date, p.offsets)
  }, [pattern, customStart, customEnd, r.date])

  // 화면 진입/패턴 변경 시 전역 state 동기화
  useEffect(() => { set({ pattern, start: range.start, end: range.end }) }, [pattern, range.start, range.end])

  const toCustom = () => { setPattern('custom'); setCustomStart(r.date); setCustomEnd(r.date); setHalf(false) }

  // 직접 선택: 첫 탭=시작, 둘째 탭=종료(자동 정렬).
  const pick = (day) => {
    if (pattern !== 'custom') return
    if (!half) { setCustomStart(day); setCustomEnd(day); setHalf(true) }
    else {
      if (day >= customStart) setCustomEnd(day)
      else { setCustomEnd(customStart); setCustomStart(day) }
      setHalf(false)
    }
  }

  const next = () => go('taste')

  return (
    <>
      <AppBar onBack={back} title="동선 만들기" />
      <div className="scr scr-body" style={{ padding: '14px 24px 8px' }}>
        <div className="page-title">언제<br />다녀올까요?</div>
        <div className="page-sub">{r.name} ({r.date.slice(5).replace('-', '.')}) 기준으로 짜드릴게요.</div>

        {/* 패턴 4종 + 직접 선택 */}
        <div style={{ marginTop: 28 }}>
          <div className="field-label">일정 패턴</div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 9, marginTop: 12 }}>
            {PATTERNS.map((p) => (
              <button key={p.key} className={`seg`} onClick={() => setPattern(p.key)} style={{ display: 'block' }}>
                <div className={`opt ${pattern === p.key ? 'active' : ''}`} style={{ width: '100%' }}>
                  {p.label}
                  <span className="sub">{p.sub}</span>
                </div>
              </button>
            ))}
            <button className="seg" onClick={toCustom} style={{ display: 'block', gridColumn: '1 / -1' }}>
              <div className={`opt ${pattern === 'custom' ? 'active' : ''}`} style={{ width: '100%' }}>
                직접 선택
                <span className="sub">날짜를 눌러 지정</span>
              </div>
            </button>
          </div>
        </div>

        {/* 미니 캘린더 — 대회일 마커 + 범위 하이라이트 (직접 선택 시 탭 가능) */}
        <div style={{ marginTop: 28 }}>
          <div className="field-label">선택 범위</div>
          <MiniCalendar raceDate={r.date} start={range.start} end={range.end} interactive={pattern === 'custom'} onPick={pick} />
          <div className="field-hint">
            {pattern === 'custom' && half
              ? '종료일을 눌러주세요'
              : `${range.start.slice(5).replace('-', '.')} ~ ${range.end.slice(5).replace('-', '.')} · ${diffDays(range.start, range.end) + 1}일`}
          </div>
        </div>
      </div>

      <div className="cta-bar">
        <button className="btn btn-primary" onClick={next}>다음</button>
      </div>
    </>
  )
}

function MiniCalendar({ raceDate, start, end, interactive = false, onPick }) {
  const base = parseDate(raceDate)
  const year = base.getFullYear()
  const month = base.getMonth()
  const first = new Date(year, month, 1)
  const startPad = first.getDay()
  const daysIn = new Date(year, month + 1, 0).getDate()
  const cells = []
  for (let i = 0; i < startPad; i++) cells.push(null)
  for (let d = 1; d <= daysIn; d++) cells.push(fmtDate(new Date(year, month, d)))

  const inRange = (s) => s && s >= start && s <= end
  const isRace = (s) => s === raceDate

  return (
    <div style={{ marginTop: 12, border: '1px solid var(--c-line-2)', borderRadius: 16, padding: 14 }}>
      <div style={{ textAlign: 'center', fontWeight: 800, fontSize: 14, marginBottom: 10 }}>{year}.{String(month + 1).padStart(2, '0')}</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7,1fr)', gap: 4 }}>
        {['일', '월', '화', '수', '목', '금', '토'].map((w) => (
          <div key={w} style={{ textAlign: 'center', fontSize: 11, color: 'var(--c-ink-5)', fontWeight: 700, paddingBottom: 4 }}>{w}</div>
        ))}
        {cells.map((s, i) => {
          const cellStyle = {
            textAlign: 'center', fontSize: 13, padding: '7px 0', borderRadius: 8,
            fontWeight: isRace(s) ? 800 : 600,
            background: isRace(s) ? 'var(--c-primary)' : inRange(s) ? 'var(--c-primary-soft)' : 'transparent',
            color: isRace(s) ? '#fff' : inRange(s) ? 'var(--c-primary)' : s ? 'var(--c-ink-2)' : 'transparent',
            border: 'none', fontFamily: 'inherit',
          }
          if (interactive && s) {
            return (
              <button key={i} onClick={() => onPick(s)} style={{ ...cellStyle, cursor: 'pointer' }}>
                {Number(s.slice(8, 10))}
              </button>
            )
          }
          return <div key={i} style={cellStyle}>{s ? Number(s.slice(8, 10)) : ''}</div>
        })}
      </div>
      <div style={{ display: 'flex', gap: 14, marginTop: 10, justifyContent: 'center', fontSize: 11, color: 'var(--c-ink-4)' }}>
        <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span style={{ width: 10, height: 10, borderRadius: 3, background: 'var(--c-primary)' }} />대회일</span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span style={{ width: 10, height: 10, borderRadius: 3, background: 'var(--c-primary-soft)' }} />여행 기간</span>
      </div>
    </div>
  )
}
