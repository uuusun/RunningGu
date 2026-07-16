import { useApp } from '../store/appState.jsx'
import Icon from '../components/Icon.jsx'

// 동선 탭 빈 상태 — 아직 만든 동선이 없을 때(대회 미선택). 대회로 유도.
export default function RouteScreen() {
  const { dispatch } = useApp()
  return (
    <>
      <div className="appbar" style={{ padding: '8px 22px 14px' }}>
        <span className="title" style={{ fontSize: 20, fontWeight: 800 }}>동선</span>
      </div>
      <div className="scr scr-body" style={{ paddingTop: 6 }}>
        <div className="empty">
          <Icon name="route" size={36} stroke={1.6} style={{ color: 'var(--c-ink-6)' }} />
          <div className="e-title" style={{ marginTop: 12 }}>아직 만든 동선이 없어요.</div>
          <div>대회를 고르면 전·후 여행 동선을 짜드려요.</div>
          <button className="e-link" onClick={() => dispatch({ type: 'RESET_TO', screen: 'home' })}>대회 둘러보기</button>
        </div>
      </div>
    </>
  )
}
