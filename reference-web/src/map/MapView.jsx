import { useEffect, useRef, useState } from 'react'
import { ensureKakao } from '../lib/runninggu/index.js'

// 지도 어댑터 — 카카오맵 우선, 불가 시 SVG 폴백.
// props: pins[{n,id,lat,lng,title,catKey}], activeId, onPinClick, accent, height
//        polyline[[lat,lng],...] — 경로선(코스 구간) 표시용. pins 없이 단독 사용 가능.
export default function MapView({ pins = [], polyline = null, connectPins = true, showLegend = true, activeId, onPinClick, accent = 'var(--c-primary)', height = 300 }) {
  const [hasKakao, setHasKakao] = useState(false)
  useEffect(() => { let m = true; ensureKakao().then((k) => m && setHasKakao(!!k)); return () => { m = false } }, [])

  const P = { pins, polyline, connectPins, showLegend, activeId, onPinClick, accent, height }
  return hasKakao ? <KakaoMap {...P} /> : <SvgMap {...P} />
}

// ── 카카오맵 ──
function KakaoMap({ pins, polyline, connectPins = true, showLegend = true, activeId, onPinClick, accent, height }) {
  const el = useRef(null)
  const map = useRef(null)
  const overlays = useRef([])
  const poly = useRef(null)
  const coursePoly = useRef(null)
  const lastSig = useRef('')

  useEffect(() => {
    const kakao = window.kakao
    if (!kakao || !el.current) return
    map.current = new kakao.maps.Map(el.current, { center: new kakao.maps.LatLng(36.5, 127.8), level: 6 })
  }, [])

  // 코스 경로선(polyline prop) — 핀과 독립적으로 그린다.
  useEffect(() => {
    const kakao = window.kakao
    const m = map.current
    if (!kakao || !m) return
    if (coursePoly.current) { coursePoly.current.setMap(null); coursePoly.current = null }
    if (!polyline || polyline.length < 2) return
    const path = polyline.map(([la, ln]) => new kakao.maps.LatLng(la, ln))
    coursePoly.current = new kakao.maps.Polyline({ path, strokeWeight: 5, strokeColor: '#2B5CFF', strokeOpacity: 0.95 })
    coursePoly.current.setMap(m)
    const bounds = new kakao.maps.LatLngBounds()
    path.forEach((ll) => bounds.extend(ll))
    m.setBounds(bounds, 30, 30, 30, 30)
  }, [polyline])

  // 핀/폴리라인 재구축 (편집·날짜변경·활성변경 시 파생 재계산)
  useEffect(() => {
    const kakao = window.kakao
    const m = map.current
    if (!kakao || !m) return
    overlays.current.forEach((o) => o.setMap(null)); overlays.current = []
    if (poly.current) { poly.current.setMap(null); poly.current = null }
    if (!pins.length) return

    const path = pins.map((p) => new kakao.maps.LatLng(p.lat, p.lng))
    if (connectPins) {
      poly.current = new kakao.maps.Polyline({ path, strokeWeight: 4, strokeColor: '#2B5CFF', strokeOpacity: 0.9 })
      poly.current.setMap(m)
    }

    pins.forEach((p) => {
      const active = p.id === activeId
      const node = document.createElement('div')
      node.className = 'pin' + (active ? ' active' : '')
      node.style.background = accent
      node.textContent = p.n
      node.onclick = () => onPinClick && onPinClick(p.id)
      const ov = new kakao.maps.CustomOverlay({ position: new kakao.maps.LatLng(p.lat, p.lng), content: node, yAnchor: 0.5, xAnchor: 0.5, zIndex: active ? 5 : 2 })
      ov.setMap(m)
      overlays.current.push(ov)
    })

    // bounds 는 핀 구성이 바뀔 때만 (스크롤로 activeId만 변하면 유지).
    // 경로선(polyline)이 있으면 그쪽 effect 가 bounds 를 잡으므로 여기선 건너뜀.
    const hasPolyline = polyline && polyline.length >= 2
    const sig = pins.map((p) => p.id).join(',')
    if (sig !== lastSig.current) {
      lastSig.current = sig
      if (!hasPolyline) {
        const bounds = new kakao.maps.LatLngBounds()
        path.forEach((ll) => bounds.extend(ll))
        m.setBounds(bounds, 36, 36, 36, 36)
      }
    } else if (activeId) {
      const p = pins.find((x) => x.id === activeId)
      if (p) m.panTo(new kakao.maps.LatLng(p.lat, p.lng))
    }
  }, [pins, accent, activeId, connectPins, polyline])

  return (
    <div className="mapwrap" style={{ height }}>
      <div ref={el} className="map-kakao" />
      {showLegend && <Legend />}
    </div>
  )
}

// ── SVG 폴백 ──
function SvgMap({ pins, polyline, connectPins = true, showLegend = true, activeId, onPinClick, accent, height }) {
  const W = 420
  const H = height
  const pad = 46
  // 바운드 계산엔 핀 + 코스 경로선 좌표를 모두 포함.
  const linePts = (polyline || []).map(([lat, lng]) => ({ lat, lng }))
  const allPts = [...pins, ...linePts]
  const lats = allPts.map((p) => p.lat)
  const lngs = allPts.map((p) => p.lng)
  let minLat = Math.min(...lats), maxLat = Math.max(...lats)
  let minLng = Math.min(...lngs), maxLng = Math.max(...lngs)
  if (!allPts.length) { minLat = maxLat = 36.5; minLng = maxLng = 127.8 }
  // 범위가 0이면 패딩 부여
  if (maxLat - minLat < 1e-4) { minLat -= 0.01; maxLat += 0.01 }
  if (maxLng - minLng < 1e-4) { minLng -= 0.01; maxLng += 0.01 }

  const toXY = (p) => {
    const x = pad + ((p.lng - minLng) / (maxLng - minLng)) * (W - pad * 2)
    const y = pad + (1 - (p.lat - minLat) / (maxLat - minLat)) * (H - pad * 2)
    return { x, y }
  }
  const pts = pins.map(toXY)
  const line = pts.map((pt, i) => `${i === 0 ? 'M' : 'L'} ${pt.x.toFixed(1)} ${pt.y.toFixed(1)}`).join(' ')
  const coursePts = linePts.map(toXY)
  const courseLine = coursePts.map((pt, i) => `${i === 0 ? 'M' : 'L'} ${pt.x.toFixed(1)} ${pt.y.toFixed(1)}`).join(' ')

  return (
    <div className="mapwrap" style={{ height }}>
      <svg width="100%" height={H} viewBox={`0 0 ${W} ${H}`} style={{ position: 'absolute', inset: 0 }} preserveAspectRatio="xMidYMid slice">
        <rect width={W} height={H} fill="#E7EDE9" />
        <rect x="22" y="24" width="120" height="74" rx="14" fill="#D8E8CF" />
        <rect x="260" y="30" width="130" height="60" rx="14" fill="#D8E8CF" />
        <ellipse cx="320" cy={H - 70} rx="100" ry="56" fill="#BFD9EA" />
        <g stroke="#F3F0E7" strokeWidth="9" strokeLinecap="round" fill="none">
          <path d={`M-10 ${H * 0.5} C 90 ${H * 0.46} 150 ${H * 0.33} 230 ${H * 0.4} S 380 ${H * 0.46} 430 ${H * 0.4}`} />
          <path d={`M170 -10 C 160 ${H * 0.27} 195 ${H * 0.5} 170 ${H + 10}`} />
        </g>
        {connectPins && pts.length > 1 && <path d={line} fill="none" stroke="#2B5CFF" strokeWidth="4.5" strokeLinecap="round" strokeLinejoin="round" opacity="0.95" />}
        {coursePts.length > 1 && <path d={courseLine} fill="none" stroke="#2B5CFF" strokeWidth="4.5" strokeLinecap="round" strokeLinejoin="round" opacity="0.95" />}
        {coursePts.length > 1 && <circle cx={coursePts[0].x} cy={coursePts[0].y} r="7" fill="#2B5CFF" stroke="#fff" strokeWidth="3" />}
      </svg>
      {pins.map((p, i) => (
        <button
          key={p.id}
          className={`pin ${p.id === activeId ? 'active' : ''}`}
          style={{ left: pts[i].x, top: pts[i].y, background: accent }}
          onClick={() => onPinClick && onPinClick(p.id)}
          aria-label={`${p.n}. ${p.title}`}
        >
          {p.n}
        </button>
      ))}
      {showLegend && <Legend />}
    </div>
  )
}

function Legend() {
  return (
    <div className="map-legend">
      <span className="lg"><span className="sw" style={{ background: 'var(--c-primary)' }} />동선</span>
      <span className="lg"><span className="sw" style={{ background: 'var(--c-orange)' }} />회복일</span>
    </div>
  )
}
