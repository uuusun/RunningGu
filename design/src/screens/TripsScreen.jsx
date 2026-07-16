import { useApp } from '../store/appState.jsx'
import Icon from '../components/Icon.jsx'
import { tripRangeLabel } from '../lib/runninggu/index.js'

export default function TripsScreen() {
  const { state, dispatch } = useApp()
  const trips = state.savedTrips

  const open = (t) => {
    dispatch({ type: 'SET', patch: { race: t.race, event: t.event, themes: t.themes || ['tour', 'food'], start: t.start, end: t.end, days: t.days, recovery: t.recovery, activeDay: 0 } })
    dispatch({ type: 'RESET_TO', screen: 'result' })
    dispatch({ type: 'TAB', tab: 'route' })
  }

  return (
    <>
      <div className="appbar" style={{ padding: '8px 22px 14px' }}>
        <span className="title" style={{ fontSize: 20, fontWeight: 800 }}>내 여행</span>
      </div>
      <div className="scr scr-body" style={{ paddingTop: 6 }}>
        {trips.length === 0 ? (
          <div className="empty">
            <Icon name="bookmark" size={36} stroke={1.6} style={{ color: 'var(--c-ink-6)' }} />
            <div className="e-title" style={{ marginTop: 12 }}>아직 저장한 동선이 없어요.</div>
            <div>대회를 골라 첫 동선을 만들어 보세요.</div>
            <button className="e-link" onClick={() => dispatch({ type: 'RESET_TO', screen: 'home' })}>대회 둘러보기</button>
          </div>
        ) : (
          trips.map((t) => (
            <button key={t.id} className="saved-card" onClick={() => open(t)} style={{ display: 'block', width: 'calc(100% - 44px)', textAlign: 'left' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div className="st">{t.region} {rangeLen(t)}</div>
                  <div className="ss">{t.raceName} · {t.event}</div>
                </div>
                {t.recovery && <span className="src-badge synth">{t.recovery.label}</span>}
              </div>
              <div className="ss" style={{ marginTop: 8 }}>{tripRangeLabel(t.start, t.end)} · {t.days.reduce((n, d) => n + d.blocks.length, 0)}곳</div>
            </button>
          ))
        )}
      </div>
    </>
  )
}

function rangeLen(t) {
  const n = t.days.length
  return n <= 1 ? '당일치기' : `${n - 1}박 ${n}일`
}
