import Icon from './Icon.jsx'
import { CAT_LABEL } from '../lib/runninggu/index.js'

// iOS 스타일 상태바 (9:41 + 신호/배터리)
export function StatusBar() {
  return (
    <div className="statusbar">
      <span className="time">9:41</span>
      <div className="icons">
        <svg width="18" height="12" viewBox="0 0 18 12"><g fill="currentColor"><rect x="0" y="8" width="3" height="4" rx="1" /><rect x="5" y="5" width="3" height="7" rx="1" /><rect x="10" y="2" width="3" height="10" rx="1" /><rect x="15" y="0" width="3" height="12" rx="1" opacity=".3" /></g></svg>
        <svg width="26" height="13" viewBox="0 0 26 13"><rect x="0.5" y="0.5" width="22" height="12" rx="3" fill="none" stroke="currentColor" strokeOpacity=".35" /><rect x="2" y="2" width="16.5" height="9" rx="1.5" fill="currentColor" /><rect x="23.5" y="4" width="2" height="5" rx="1" fill="currentColor" fillOpacity=".4" /></svg>
      </div>
    </div>
  )
}

// 뒤로/타이틀/우측액션 헤더
export function AppBar({ title, onBack, right }) {
  return (
    <div className="appbar">
      {onBack && (
        <button className="iconbtn" onClick={onBack} aria-label="뒤로">
          <Icon name="back" size={26} stroke={2.2} />
        </button>
      )}
      {title && <span className="title">{title}</span>}
      <span className="spacer" />
      {right && <div className="actions">{right}</div>}
    </div>
  )
}

// 카테고리 태그(색 + 텍스트 — 접근성). catKey 별 토큰 색.
export function CatTag({ catKey, children }) {
  const label = children || CAT_LABEL[catKey] || catKey
  const c = `var(--cat-${catKey}-fg, var(--c-ink-4))`
  const bg = `var(--cat-${catKey}-bg, var(--c-fill))`
  return <span className="cat-tag" style={{ color: c, background: bg }}>{label}</span>
}

// LIVE/샘플 소스 배지
export function SourceBadge({ source }) {
  const map = { live: ['live', 'LIVE'], sample: ['sample', '샘플'], synth: ['synth', '샘플'] }
  const [cls, label] = map[source] || map.sample
  return <span className={`src-badge ${cls}`}>{label}</span>
}

// 회복 모드 배지(엔진 recovery 결과)
export function RecoveryBadge({ recovery }) {
  if (!recovery) return null
  return (
    <div className="recovery" role="note">
      <div className="icon"><Icon name="recover" size={21} stroke={2} style={{ color: '#fff' }} /></div>
      <div>
        <div className="label">{recovery.label}</div>
        <div className="text">{recovery.text}</div>
      </div>
    </div>
  )
}
