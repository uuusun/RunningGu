import { useApp } from './store/appState.jsx'
import { StatusBar } from './components/chrome.jsx'
import TabBar from './components/TabBar.jsx'
import HomeScreen from './screens/HomeScreen.jsx'
import RaceScreen from './screens/RaceScreen.jsx'
import PlanScreen from './screens/PlanScreen.jsx'
import TasteScreen from './screens/TasteScreen.jsx'
import StayScreen from './screens/StayScreen.jsx'
import ResultScreen from './screens/ResultScreen.jsx'
import TripsScreen from './screens/TripsScreen.jsx'
import CoursesScreen from './screens/CoursesScreen.jsx'
import RouteScreen from './screens/RouteScreen.jsx'

const SCREENS = {
  home: HomeScreen,
  race: RaceScreen,
  plan: PlanScreen,
  taste: TasteScreen,
  stay: StayScreen,
  result: ResultScreen,
  trips: TripsScreen,
  courses: CoursesScreen,
  route: RouteScreen,
}
// 하단 탭바를 노출하는 최상위 화면
const TOP_LEVEL = new Set(['home', 'result', 'trips', 'courses', 'route'])

export default function App() {
  const { state } = useApp()
  const Screen = SCREENS[state.screen] || HomeScreen
  return (
    <div className="app-canvas">
      <div className="phone">
        <StatusBar />
        <Screen />
        {TOP_LEVEL.has(state.screen) && <TabBar />}
        {state.toast && (
          <div className="toast-wrap">
            <div className="toast success">{state.toast}</div>
          </div>
        )}
      </div>
    </div>
  )
}
