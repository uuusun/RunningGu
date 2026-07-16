import { useEffect, useState } from 'react'
import { useApp } from '../store/appState.jsx'
import { AppBar, SourceBadge } from '../components/chrome.jsx'
import Icon from '../components/Icon.jsx'
import { searchPOIs, LODGING_CAT } from '../lib/runninggu/index.js'

export default function StayScreen() {
  const { state, back, runEngine, set } = useApp()
  const r = state.race
  const [list, setList] = useState([])
  const [source, setSource] = useState(null)
  const [loading, setLoading] = useState(true)
  const [picked, setPicked] = useState(state.stay || null)
  const [q, setQ] = useState('')

  useEffect(() => {
    let on = true
    setLoading(true)
    searchPOIs({ cat: LODGING_CAT, center: { lat: r.lat, lng: r.lng }, raceId: r.id, count: 8 }).then((res) => {
      if (!on) return
      setList(res.places); setSource(res.source); setLoading(false)
    })
    return () => { on = false }
  }, [r.id])

  const filtered = q ? list.filter((p) => (p.name + (p.addr || '')).includes(q)) : list

  const submit = () => { set({ stay: picked }); runEngine({ stay: picked }) }

  return (
    <>
      <AppBar onBack={back} title="동선 만들기" />
      <div className="scr scr-body" style={{ padding: '14px 24px 8px' }}>
        <div className="page-title">어디서<br />묵을까요?</div>
        <div className="page-sub">{state.building ? '' : '건너뛰면 대회장 주변으로 잡아드려요.'}</div>

        <label className="sheet-search" style={{ margin: '20px 0 0' }}>
          <Icon name="search" size={18} stroke={2} style={{ color: 'var(--c-ink-5)' }} />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="숙소 검색 · 카카오 로컬"
            style={{ border: 'none', outline: 'none', background: 'transparent', flex: 1, fontSize: 13, color: 'var(--c-ink)', fontFamily: 'inherit' }} />
        </label>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '18px 0 10px' }}>
          <div className="field-label" style={{ margin: 0 }}>대회장 주변 추천</div>
          {source && <SourceBadge source={source} />}
        </div>

        {loading && <div className="empty">숙소를 찾는 중…</div>}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {filtered.map((p) => {
            const sel = picked && picked.name === p.name
            return (
              <button key={p.name} className="cand" onClick={() => setPicked(p)} style={{ textAlign: 'left', borderBottom: '1px solid var(--c-line-4)' }}>
                <div className="thumb" />
                <div className="info">
                  <div className="cname">{p.name}</div>
                  <div className="cmeta">{p.addr || '숙소'} · {p.desc}</div>
                </div>
                <span className={`pick ${sel ? 'primary' : ''}`}>{sel ? '선택됨' : '선택'}</span>
              </button>
            )
          })}
        </div>
      </div>

      <div className="cta-bar">
        <button className="btn btn-primary" disabled={state.building} onClick={submit}>
          {state.building ? '동선 짜는 중…' : picked ? '이 숙소로 동선 추천받기' : '숙소 없이 추천받기'}
        </button>
      </div>
    </>
  )
}
