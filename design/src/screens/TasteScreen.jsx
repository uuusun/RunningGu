import { useState } from 'react'
import { useApp } from '../store/appState.jsx'
import { AppBar } from '../components/chrome.jsx'
import Icon from '../components/Icon.jsx'
import { RECOVERY, CATS } from '../lib/runninggu/index.js'

const EVENTS = ['5K', '10K', '하프', '풀']

export default function TasteScreen() {
  const { state, back, go, set } = useApp()
  const r = state.race
  const [event, setEvent] = useState(state.event || (r.eventTypes.includes('하프') ? '하프' : r.eventTypes[0]))
  const [themes, setThemes] = useState(state.themes?.length ? state.themes : ['tour', 'food'])

  const rule = RECOVERY[event]
  const toggleTheme = (k) => setThemes((t) => (t.includes(k) ? t.filter((x) => x !== k) : [...t, k]))

  const next = () => { set({ event, themes }); go('stay') }
  const fromRace = r.eventTypes.includes(event)

  return (
    <>
      <AppBar onBack={back} title="동선 만들기" />
      <div className="scr scr-body" style={{ padding: '14px 24px 8px' }}>
        <div className="page-title">어떻게 즐기고<br />싶으세요?</div>
        <div className="page-sub">{r.name} 기준으로 짜드릴게요.</div>

        {/* 종목 (단일) */}
        <div style={{ marginTop: 28 }}>
          <div className="field-label">종목 <span style={{ color: 'var(--c-orange)', fontWeight: 700 }}>· 회복강도 {rule.intensity}</span></div>
          <div className="seg" style={{ marginTop: 11 }}>
            {EVENTS.map((e) => (
              <button key={e} className={`opt ${event === e ? 'active' : ''}`} onClick={() => setEvent(e)}>{e}</button>
            ))}
          </div>
          <div className="field-hint">{fromRace ? '대회에서 가져왔어요 · 바꿀 수 있어요' : '이 대회 종목엔 없지만 선택할 수 있어요'}</div>
        </div>

        {/* 테마 (복수) */}
        <div style={{ marginTop: 28 }}>
          <div className="field-label">여행 취향 <span style={{ color: 'var(--c-ink-7)', fontWeight: 600 }}>· 복수 선택</span></div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 9, marginTop: 12 }}>
            {CATS.map((c) => (
              <button key={c.key} className={`tchip ${themes.includes(c.key) ? 'active' : ''}`} onClick={() => toggleTheme(c.key)}>
                <span className="dot" />{c.label}
              </button>
            ))}
          </div>
        </div>

        {/* 회복 안내 */}
        {rule.noHard && (
          <div className="infobox" style={{ marginTop: 30 }}>
            <Icon name="info" size={20} stroke={2} style={{ color: 'var(--c-primary)' }} />
            <div>{event}는 완주 다음날 회복이 중요해요. <b>D+1은 고강도 일정을 빼고</b> 온천·가벼운 산책 위주로 동선을 짭니다.</div>
          </div>
        )}
      </div>

      <div className="cta-bar">
        <button className="btn btn-primary" disabled={!themes.length} onClick={next}>
          다음<Icon name="chevronRight" size={19} stroke={2.6} style={{ color: '#fff' }} />
        </button>
      </div>
    </>
  )
}
