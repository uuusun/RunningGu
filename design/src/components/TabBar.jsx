import Icon from './Icon.jsx'
import { useApp } from '../store/appState.jsx'

// 하단 탭바 — 캘린더 · 동선 · 러닝코스 · 내 여행 (EXPERIENCE.md IA)
const TABS = [
  { key: 'calendar', label: '캘린더', icon: 'calendar', screen: 'home' },
  { key: 'route', label: '동선', icon: 'route' },
  { key: 'courses', label: '러닝코스', icon: 'courses' },
  { key: 'trips', label: '내 여행', icon: 'bookmark', screen: 'trips' },
]

export default function TabBar() {
  const { state, dispatch } = useApp()

  const onTab = (t) => {
    dispatch({ type: 'TAB', tab: t.key })
    if (t.key === 'calendar') dispatch({ type: 'RESET_TO', screen: 'home' })
    else if (t.key === 'trips') dispatch({ type: 'RESET_TO', screen: 'trips' })
    else if (t.key === 'route') {
      // 동선: 만든 결과가 있으면 결과로, 없으면 빈 안내 화면(항상 눈에 보이게 반응)
      dispatch({ type: 'RESET_TO', screen: state.days.length ? 'result' : 'route' })
    } else if (t.key === 'courses') dispatch({ type: 'RESET_TO', screen: 'courses' })
  }

  const activeKey =
    state.screen === 'trips' ? 'trips'
    : state.screen === 'result' || state.screen === 'route' ? 'route'
    : state.screen === 'courses' ? 'courses'
    : state.screen === 'home' ? 'calendar'
    : state.tab

  return (
    <nav className="tabbar" aria-label="주요 메뉴">
      {TABS.map((t) => (
        <button
          key={t.key}
          className={`tab ${activeKey === t.key ? 'active' : ''}`}
          onClick={() => onTab(t)}
          aria-current={activeKey === t.key ? 'page' : undefined}
        >
          <Icon name={t.icon} size={23} stroke={2} />
          <span>{t.label}</span>
        </button>
      ))}
    </nav>
  )
}
