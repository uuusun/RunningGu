import React, { createContext, useContext, useReducer, useRef, useCallback, useEffect } from 'react'
import { RAW_RACES, normalizeRaces, buildItinerary } from '../lib/runninggu/index.js'

// 화면 상태머신: home→race→plan→taste→stay→result. 뒤로가기 스택 유지.
// 대회 데이터는 파이프라인 산출물(public/data/races.json)을 로드해 교체하고,
// 로드 전·실패 시에는 내장 샘플로 동작한다(NFR-1: 키·네트워크 없이 전체 시연 가능).
const FALLBACK_RACES = normalizeRaces(RAW_RACES)

const initial = {
  screen: 'home',
  races: FALLBACK_RACES,
  stack: [],            // 뒤로가기 스택
  tab: 'calendar',      // 하단 탭(calendar|route|courses|trips)
  // 전역 동선 state
  race: null,
  pattern: null,
  start: null,
  end: null,
  event: null,
  themes: ['tour', 'food'],
  stay: null,
  days: [],
  sources: {},
  recovery: null,
  activeDay: 0,
  // 편집/저장/시트
  editMode: false,
  building: false,
  sheet: null,          // { dayIndex, blockId, catKey } 후보 교체/추가
  savedTrips: [],
  toast: null,
}

function reducer(state, a) {
  switch (a.type) {
    case 'GO':
      return { ...state, stack: [...state.stack, state.screen], screen: a.screen }
    case 'BACK': {
      if (!state.stack.length) return state
      const stack = state.stack.slice()
      const screen = stack.pop()
      return { ...state, stack, screen, editMode: false, sheet: null }
    }
    case 'TAB':
      return { ...state, tab: a.tab }
    case 'RESET_TO':
      return { ...state, stack: [], screen: a.screen, editMode: false, sheet: null }
    case 'SET':
      return { ...state, ...a.patch }
    case 'SELECT_RACE': {
      const r = a.race
      const ev = r.eventTypes.includes('하프') ? '하프' : r.eventTypes[0] || '5K'
      return { ...state, race: r, event: ev, stack: [...state.stack, state.screen], screen: 'race' }
    }
    case 'BUILD_START':
      return { ...state, building: true }
    case 'BUILD_DONE':
      return {
        ...state, building: false,
        days: a.days, sources: a.sources, recovery: a.recovery, activeDay: 0,
        stack: [...state.stack, state.screen], screen: 'result',
      }
    case 'SET_DAYS':                // 편집 결과 반영(지도·스크롤 파생 재계산은 컴포넌트가 담당)
      return { ...state, days: a.days }
    case 'ACTIVE_DAY':
      return { ...state, activeDay: a.i }
    case 'TOGGLE_EDIT':
      return { ...state, editMode: !state.editMode, sheet: null }
    case 'OPEN_SHEET':
      return { ...state, sheet: a.sheet }
    case 'CLOSE_SHEET':
      return { ...state, sheet: null }
    case 'SAVE_TRIP': {
      const trip = a.trip
      return { ...state, savedTrips: [trip, ...state.savedTrips.filter((t) => t.id !== trip.id)], toast: a.toast || '동선을 저장했어요' }
    }
    case 'TOAST':
      return { ...state, toast: a.toast }
    case 'RACES':
      return { ...state, races: a.races }
    default:
      return state
  }
}

export const AppContext = createContext(null)
const Ctx = AppContext

export function AppProvider({ children }) {
  const [state, dispatch] = useReducer(reducer, initial)
  const toastTimer = useRef(null)

  // 실데이터 로드(G-01). 실패해도 FALLBACK_RACES로 계속 동작.
  useEffect(() => {
    fetch(`${import.meta.env.BASE_URL}data/races.json`)
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`))))
      .then((list) => {
        const races = normalizeRaces(list)
        if (races.length) dispatch({ type: 'RACES', races })
      })
      .catch(() => {})
  }, [])

  const toast = useCallback((msg) => {
    dispatch({ type: 'TOAST', toast: msg })
    clearTimeout(toastTimer.current)
    toastTimer.current = setTimeout(() => dispatch({ type: 'TOAST', toast: null }), 2200)
  }, [])

  // 엔진 실행 → days 생성 후 결과 화면으로.
  const runEngine = useCallback(async (override = {}) => {
    const s = { ...state, ...override }
    if (!s.race || !s.start || !s.end || !s.event) return
    dispatch({ type: 'BUILD_START' })
    try {
      const { days, sources, recovery } = await buildItinerary({
        race: s.race, stay: s.stay, event: s.event, themes: s.themes, start: s.start, end: s.end,
      })
      dispatch({ type: 'BUILD_DONE', days, sources, recovery })
    } catch (e) {
      dispatch({ type: 'BUILD_DONE', days: [], sources: {}, recovery: null })
      toast('동선을 못 불러왔어요. 다시 시도해 주세요')
    }
  }, [state, toast])

  const value = {
    state,
    races: state.races,
    dispatch,
    toast,
    runEngine,
    go: (screen) => dispatch({ type: 'GO', screen }),
    back: () => dispatch({ type: 'BACK' }),
    set: (patch) => dispatch({ type: 'SET', patch }),
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useApp() {
  const v = useContext(Ctx)
  if (!v) throw new Error('useApp must be used within AppProvider')
  return v
}
