import { useEffect, useMemo, useState } from 'react'
import Icon from '../components/Icon.jsx'
import MapView from '../map/MapView.jsx'
import { browseCourses, buildRouteNear, courseRegions, geocodePlace, searchWalkSpots } from '../lib/runninggu/index.js'

// 러닝코스(M4) — 두루누비 걷기 코스(코리아둘레길).
//  · 내 주변: 사용자 위치에서 가까운 코스를 원하는 길이로 잘라 추천 + 지도 표시
//  · 지역별: 시·도로 전체 코스 브라우징
// 코스가 실제로 있는 해안·수도권 프리셋(GPS 대체/데모용).
const PRESETS = [
  { label: '부산 해운대', lat: 35.1587, lng: 129.1604 },
  { label: '여수', lat: 34.7604, lng: 127.6622 },
  { label: '강릉', lat: 37.7519, lng: 128.8761 },
  { label: '인천 강화', lat: 37.7466, lng: 126.4880 },
  { label: '서울시청', lat: 37.5665, lng: 126.9780 },
]

export default function CoursesScreen() {
  const [mode, setMode] = useState('near')
  return (
    <>
      <div className="appbar" style={{ padding: '8px 22px 12px' }}>
        <span className="title" style={{ fontSize: 20, fontWeight: 800 }}>러닝·산책 코스</span>
      </div>
      <div style={{ padding: '0 22px 12px', flex: 'none' }}>
        <div className="toggle" role="tablist">
          <button className={`opt ${mode === 'near' ? 'active' : ''}`} onClick={() => setMode('near')}>내 주변</button>
          <button className={`opt ${mode === 'browse' ? 'active' : ''}`} onClick={() => setMode('browse')}>지역별</button>
        </div>
      </div>
      {mode === 'near' ? <NearMode /> : <BrowseMode />}
    </>
  )
}

// ── 내 주변: 지도 기반 코스 빌더 (출발지 + 목표거리 → 두루누비 왕복 경로) ──
function NearMode() {
  const [loc, setLoc] = useState(null)         // { lat, lng, label }
  const [targetKm, setTargetKm] = useState(5)
  const [locating, setLocating] = useState(true)
  const [selId, setSelId] = useState(null)
  const [q, setQ] = useState('')
  const [hits, setHits] = useState(null)       // 검색 결과 후보
  const [spots, setSpots] = useState([])       // 도시 보강: 카카오 걷기 좋은 곳

  const useGps = () => {
    if (!navigator.geolocation) { setLocating(false); return }
    setLocating(true)
    navigator.geolocation.getCurrentPosition(
      (p) => { setLoc({ lat: p.coords.latitude, lng: p.coords.longitude, label: '현재 위치' }); setLocating(false); setSelId(null); setHits(null) },
      () => setLocating(false), { timeout: 6000 },
    )
  }
  useEffect(() => { useGps() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const runSearch = async () => {
    if (!q.trim()) return
    const res = await geocodePlace(q)
    setHits(res)
    if (res[0]) { setLoc({ lat: res[0].lat, lng: res[0].lng, label: res[0].name }); setSelId(null) }
  }

  const routes = useMemo(
    () => (loc ? buildRouteNear({ lat: loc.lat, lng: loc.lng, targetKm, radiusKm: 8, limit: 12 }) : []),
    [loc, targetKm],
  )
  const selected = routes.find((r) => r.id === selId) || routes[0] || null

  // 지도: 두루누비 왕복 경로만 선으로. 도시(두루누비 없음)는 걷기 스팟을 핀으로만 표시.
  const mapPolyline = selected ? selected.routePoints : null
  // 걷기 스팟 핀(거리순) — 아래 리스트 번호와 일치. 선으로 잇지 않음.
  const spotPins = useMemo(
    () => spots.map((s, i) => ({ id: `spot-${i}`, n: i + 1, lat: s.lat, lng: s.lng, title: s.name })),
    [spots],
  )

  // 도시 보강: 위치가 바뀌면 카카오 로컬로 근처 걷기 좋은 곳(점) 조회.
  useEffect(() => {
    if (!loc) { setSpots([]); return }
    let on = true
    searchWalkSpots({ lat: loc.lat, lng: loc.lng, radiusM: 3000, limit: 12 })
      .then((s) => { if (on) setSpots(s) })
      .catch(() => { if (on) setSpots([]) })
    return () => { on = false }
  }, [loc])

  return (
    <>
      {/* 출발지 검색 — 인풋은 가로로 꽉 차고, 검색 버튼은 오른쪽 끝. */}
      <div style={{ padding: '0 22px 8px', flex: 'none' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, width: '100%', padding: '10px 12px 10px 14px', borderRadius: 'var(--r-chip)', background: 'var(--c-fill)' }}>
          <Icon name="search" size={18} stroke={2} style={{ color: 'var(--c-ink-5)', flex: 'none' }} />
          <input
            value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && runSearch()}
            placeholder="출발지 검색 (예: 경주역, 해운대)"
            style={{ border: 'none', outline: 'none', background: 'transparent', flex: 1, minWidth: 0, fontSize: 14, color: 'var(--c-ink)', fontFamily: 'inherit' }}
          />
          <button className="chip outline-ink" style={{ flex: 'none', marginLeft: 'auto', padding: '6px 14px' }} onClick={runSearch}>검색</button>
        </label>
      </div>
      {/* 출발지 칩: 내 위치 + 프리셋 */}
      <div className="chip-row scr" style={{ flex: 'none', paddingTop: 0 }}>
        <button className="chip outline-ink" onClick={useGps}><Icon name="route" size={14} stroke={2.2} />내 위치</button>
        {PRESETS.map((p) => (
          <button key={p.label} className={`chip ${loc?.label === p.label ? 'active' : ''}`}
            onClick={() => { setLoc({ ...p }); setSelId(null); setHits(null) }}>{p.label}</button>
        ))}
      </div>
      {/* 목표 거리 — 자유 선택 슬라이더 */}
      <div style={{ padding: '2px 22px 8px', flex: 'none' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
          <span style={{ fontSize: 12, color: 'var(--c-ink-5)' }}>몇 km 뛸까요</span>
          <span style={{ fontSize: 15, fontWeight: 800, color: 'var(--c-primary)' }}>{targetKm}km</span>
        </div>
        <input
          type="range" min="1" max="21" step="0.5" value={targetKm}
          onChange={(e) => setTargetKm(Number(e.target.value))}
          style={{ width: '100%', accentColor: 'var(--c-primary)' }}
          aria-label="목표 거리"
        />
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--c-ink-6)', marginTop: 2 }}>
          <span>1km</span><span>21km (풀코스 하프)</span>
        </div>
      </div>

      <div className="scr scr-body" style={{ padding: '4px 22px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        {/* 지도 우선 — 두루누비 왕복(선) + 걷기 스팟(핀) */}
        <MapView polyline={mapPolyline} pins={spotPins} connectPins={false} showLegend={false} height={230} />

        {!loc && (
          <div className="empty">
            <div className="e-title">{locating ? '위치를 확인하는 중…' : '출발지를 정해주세요.'}</div>
            <div>내 위치 허용 · 출발지 검색 · 프리셋 중 하나를 고르면 두루누비 왕복 코스를 짜드려요.</div>
          </div>
        )}

        {/* 선택된 코스 요약 */}
        {selected && (
          <div className="race-card featured" style={{ alignItems: 'flex-start' }}>
            <span style={{ flex: 'none', width: 50, height: 50, borderRadius: 14, background: 'var(--cat-nature-bg)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--cat-nature-fg)' }}>
              <Icon name="courses" size={24} stroke={2} />
            </span>
            <div className="body">
              <div className="name" style={{ fontSize: 16 }}>{selected.parentName} 왕복</div>
              <div className="place">{selected.sigun} · 출발지→진입점 {fmtM(selected.accessM)}</div>
              <div className="evt-tags">
                <span className="evt-tag hi">{selected.routeKm}km</span>
                <span className="evt-tag">약 {selected.minutes}분</span>
                <span className="evt-tag">{selected.levelLabel}</span>
              </div>
              {selected.shortfall && (
                <div style={{ fontSize: 12, color: 'var(--c-ink-5)', marginTop: 4 }}>이 코스가 짧아 목표({targetKm}km)보다 짧게 짜였어요.</div>
              )}
            </div>
          </div>
        )}

        {/* 대체 코스 */}
        {loc && routes.length > 1 && (
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink-4)' }}>다른 코스 {routes.length - 1}개</div>
        )}
        {/* 두루누비 코스가 없는 지역(수도권 등): 걷기 좋은 곳 안내 */}
        {loc && routes.length === 0 && spots.length > 0 && (
          <div className="infobox">
            <Icon name="info" size={20} stroke={2} style={{ color: 'var(--c-primary)' }} />
            <div>이 근처엔 두루누비 코스가 없어요. 대신 걸을 만한 공원·하천을 모았어요.</div>
          </div>
        )}
        {loc && routes.length === 0 && spots.length === 0 && (
          <div className="empty">
            <div className="e-title">이 근처엔 걸을 곳을 못 찾았어요.</div>
            <div>출발지를 바꾸거나 프리셋(부산·여수·강릉 등)을 눌러보세요.</div>
          </div>
        )}
        {routes.filter((r) => r.id !== selected?.id).map((c) => (
          <button key={c.id} className="race-card" onClick={() => setSelId(c.id)} style={{ textAlign: 'left', width: '100%' }}>
            <span style={{ flex: 'none', width: 50, height: 50, borderRadius: 14, background: 'var(--cat-nature-bg)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--cat-nature-fg)' }}>
              <Icon name="courses" size={24} stroke={2} />
            </span>
            <div className="body">
              <div className="name" style={{ fontSize: 16 }}>{c.parentName} 왕복</div>
              <div className="place">{c.sigun} · 진입점까지 {fmtM(c.accessM)}</div>
              <div className="evt-tags">
                <span className="evt-tag hi">{c.routeKm}km</span>
                <span className="evt-tag">약 {c.minutes}분</span>
                <span className="evt-tag">{c.levelLabel}</span>
              </div>
            </div>
          </button>
        ))}

        {/* 걷기 좋은 곳(카카오) — 거리순. 지도 핀 번호와 일치. */}
        {loc && spots.length > 0 && (
          <>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink-4)', marginTop: 4 }}>
              이 근처 걷기 좋은 곳
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--c-ink-6)' }}> · 카카오</span>
            </div>
            {spots.map((s, i) => (
              <a key={s.name + s.addr} href={s.url} target="_blank" rel="noreferrer" className="race-card" style={{ textDecoration: 'none' }}>
                <span style={{ flex: 'none', width: 50, height: 50, borderRadius: 14, background: 'var(--c-primary)', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 800, fontSize: 16 }}>
                  {i + 1}
                </span>
                <div className="body">
                  <div className="name" style={{ fontSize: 15 }}>{s.name}</div>
                  <div className="place">{s.category} · {fmtM(s.distM)}</div>
                  <div className="evt-tags"><span className="evt-tag">{s.addr}</span></div>
                </div>
              </a>
            ))}
          </>
        )}
      </div>
    </>
  )
}

const fmtM = (m) => (m < 1000 ? `${m}m` : `${(m / 1000).toFixed(1)}km`)

// ── 지역별: 브라우징 (지역 필터 한 줄 + 한 줄 메타 카드로 단순화) ──
function BrowseMode() {
  const [region, setRegion] = useState(null)
  const regions = useMemo(() => courseRegions(), [])
  const filtered = useMemo(() => browseCourses({ region }), [region])

  return (
    <>
      <div className="chip-row scr" style={{ flex: 'none', paddingTop: 2 }}>
        {regions.map((rg) => (
          <button key={rg} className={`chip ${region === rg ? 'active' : ''}`} onClick={() => setRegion(region === rg ? null : rg)}>
            {rg}{region === rg && <span className="x">✕</span>}
          </button>
        ))}
      </div>
      <div className="scr scr-body" style={{ padding: '8px 22px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--c-ink-4)' }}>
          {region || '전국'} 코스 <span style={{ color: 'var(--c-primary)' }}>{filtered.length}</span>
          <span style={{ fontWeight: 600, color: 'var(--c-ink-6)' }}> · 두루누비 걷기길</span>
        </div>
        {filtered.length === 0 && (
          <div className="empty"><div className="e-title">이 지역엔 코스가 없어요.</div></div>
        )}
        {filtered.map((c) => (
          <div key={c.id} className="race-card">
            <span style={{ flex: 'none', width: 46, height: 46, borderRadius: 13, background: 'var(--cat-nature-bg)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--cat-nature-fg)' }}>
              <Icon name="courses" size={22} stroke={2} />
            </span>
            <div className="body">
              <div className="name" style={{ fontSize: 15 }}>{c.name}</div>
              <div className="place">{c.sigun} · {c.distKm}km · {c.levelLabel}{c.minutes ? ` · 약 ${c.minutes}분` : ''}</div>
            </div>
          </div>
        ))}
      </div>
    </>
  )
}
