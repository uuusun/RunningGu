/* ================================================================
   런닝구 화면 플로우 보드 — Figma 플러그인 본체

   LAYOUT (섹션·아이템 좌표 + 커넥터)은 이 파일 위에 자동으로 붙는다.
   빌드: docs/mockup-design/shots 에서 `node build-layout.mjs`
   ================================================================ */

const PAD = LAYOUT.canvas.pad;
const IMAGES = {};          // 캡처 이름 -> imageHash
let FONT = null;

/* ── 작은 도구들 ───────────────────────────────────────────── */
function solid(hex, opacity) {
  const n = parseInt(hex.slice(1), 16);
  return {
    type: 'SOLID',
    color: { r: ((n >> 16) & 255) / 255, g: ((n >> 8) & 255) / 255, b: (n & 255) / 255 },
    opacity: opacity == null ? 1 : opacity
  };
}
const progress = (text, ratio) => figma.ui.postMessage({ type: 'progress', text, ratio });

/* 한글이 깨지지 않는 폰트를 찾아 쓴다. 없으면 Inter 로 떨어뜨린다. */
async function pickFont() {
  const prefer = ['Pretendard Variable', 'Pretendard', 'Noto Sans KR', 'Apple SD Gothic Neo', 'Malgun Gothic', 'Inter'];
  const avail = await figma.listAvailableFontsAsync();
  const byFamily = {};
  for (const f of avail) (byFamily[f.fontName.family] = byFamily[f.fontName.family] || []).push(f.fontName.style);
  for (const fam of prefer) {
    const styles = byFamily[fam];
    if (!styles) continue;
    const reg = styles.indexOf('Regular') >= 0 ? 'Regular' : styles[0];
    const bold = styles.indexOf('Bold') >= 0 ? 'Bold' : (styles.indexOf('SemiBold') >= 0 ? 'SemiBold' : reg);
    try {
      await figma.loadFontAsync({ family: fam, style: reg });
      await figma.loadFontAsync({ family: fam, style: bold });
      return { regular: { family: fam, style: reg }, bold: { family: fam, style: bold } };
    } catch (e) { /* 다음 후보로 */ }
  }
  await figma.loadFontAsync({ family: 'Inter', style: 'Regular' });
  await figma.loadFontAsync({ family: 'Inter', style: 'Bold' });
  return { regular: { family: 'Inter', style: 'Regular' }, bold: { family: 'Inter', style: 'Bold' } };
}

function text(parent, str, x, y, size, color, bold, width) {
  const t = figma.createText();
  parent.appendChild(t);
  t.fontName = bold ? FONT.bold : FONT.regular;
  t.characters = str;
  t.fontSize = size;
  t.lineHeight = { unit: 'PERCENT', value: 140 };
  t.fills = [solid(color)];
  if (width) { t.textAutoResize = 'HEIGHT'; t.resize(width, t.height); }
  else t.textAutoResize = 'WIDTH_AND_HEIGHT';
  t.x = x; t.y = y;
  return t;
}

async function drawArrow(page, pts, style, label) {
  const minX = Math.min.apply(null, pts.map(p => p.x));
  const minY = Math.min.apply(null, pts.map(p => p.y));
  const net = {
    vertices: pts.map((p, i) => ({
      x: p.x - minX, y: p.y - minY,
      strokeCap: i === pts.length - 1 ? 'ARROW_LINES' : 'NONE'
    })),
    segments: pts.slice(1).map((_, i) => ({ start: i, end: i + 1 })),
    regions: []
  };
  const vec = figma.createVector();
  page.appendChild(vec);
  if (vec.setVectorNetworkAsync) await vec.setVectorNetworkAsync(net);
  else vec.vectorNetwork = net;
  vec.x = minX; vec.y = minY;
  vec.fills = [];
  vec.strokes = [solid(style.color)];
  vec.strokeWeight = style.weight;
  vec.strokeJoin = 'ROUND';
  vec.name = label;

  /* 라벨은 가장 긴 구간 위에 얹는다 — 짧은 꺾임에 걸치면 읽기 어렵다 */
  let best = 0, bestLen = -1;
  for (let i = 1; i < pts.length; i++) {
    const len = Math.abs(pts[i].x - pts[i - 1].x) + Math.abs(pts[i].y - pts[i - 1].y);
    if (len > bestLen) { bestLen = len; best = i; }
  }
  const p0 = pts[best - 1], p1 = pts[best];
  const cx = (p0.x + p1.x) / 2, cy = (p0.y + p1.y) / 2;
  const t = text(page, label, 0, 0, style.fontSize, style.color, true);
  const horizontal = Math.abs(p1.x - p0.x) >= Math.abs(p1.y - p0.y);
  t.x = horizontal ? cx - t.width / 2 : cx + 12;
  t.y = horizontal ? cy - t.height - 8 : cy - t.height / 2;
}

/* ── 보드 생성 ─────────────────────────────────────────────── */
async function build() {
  FONT = await pickFont();

  const page = figma.createPage();
  page.name = '화면 플로우 v2';
  figma.currentPage = page;

  const rects = rgBuildRects(LAYOUT);   // 커넥터가 쓸 절대 좌표 — router.js 와 공유
  const total = LAYOUT.sections.length;

  for (let si = 0; si < total; si++) {
    const sec = LAYOUT.sections[si];
    progress('섹션 그리는 중 ' + (si + 1) + ' / ' + total + ' · ' + sec.title, (si + 1) / (total + 1));

    const frame = figma.createFrame();
    page.appendChild(frame);
    frame.name = sec.title;
    frame.x = sec.x; frame.y = sec.y;
    frame.resize(sec.w, sec.h);
    frame.fills = [solid(sec.fill)];
    frame.cornerRadius = 28;
    frame.clipsContent = false;

    text(frame, sec.title, PAD, 46, 40, '#15161B', true);
    text(frame, sec.note, PAD, 108, 20, '#54565E', false, Math.min(sec.w - PAD * 2, 2200));

    for (const it of sec.items) {
      const hash = IMAGES[it.name];
      const r = figma.createRectangle();
      frame.appendChild(r);
      r.name = it.name;
      r.x = it.x; r.y = it.y;
      r.resize(it.w, it.h);
      if (hash) {
        r.fills = [{ type: 'IMAGE', imageHash: hash, scaleMode: 'FILL' }];
      } else {
        /* 이미지를 못 받은 화면은 빈 자리로 남겨 무엇이 빠졌는지 보이게 한다 */
        r.fills = [solid('#FFFFFF')];
        r.strokes = [solid('#C9CAD0')];
        r.dashPattern = [10, 8];
        r.cornerRadius = 20;
        text(frame, it.name + '\n(이미지 없음)', it.x + 24, it.y + it.h / 2 - 30, 22, '#86878E', false, it.w - 48);
      }
      text(frame, it.label, it.x, it.y + it.h + 14, 24, '#15161B', false, it.w);
    }
  }

  progress('화살표 ' + LAYOUT.connectors.length + '개 그리는 중…', 0.94);
  for (let i = 0; i < LAYOUT.connectors.length; i++) {
    const c = LAYOUT.connectors[i];
    const a = rects[c.from], b = rects[c.to];
    if (!a || !b) continue;
    await drawArrow(page, rgRoute(a, b, i), LAYOUT.style[c.kind], c.label);
  }

  figma.viewport.scrollAndZoomIntoView(page.children);
  const got = Object.keys(IMAGES).length;
  const want = LAYOUT.sections.reduce((n, s) => n + s.items.length, 0);
  progress('완료 · 화면 ' + want + '개 중 이미지 ' + got + '개, 화살표 ' + LAYOUT.connectors.length + '개', 1);
  figma.notify('플로우 보드를 그렸어요 — 화면 ' + want + ' · 화살표 ' + LAYOUT.connectors.length +
    (got < want ? ' (이미지 ' + (want - got) + '개 누락)' : ''));
}

/* ── 메시지 루프 ───────────────────────────────────────────── */
figma.showUI(__html__, { width: 340, height: 300 });

figma.ui.onmessage = async msg => {
  if (msg.type === 'close') { figma.closePlugin(); return; }
  if (msg.type === 'img') {
    try { IMAGES[msg.name] = figma.createImage(msg.bytes).hash; }
    catch (e) { figma.ui.postMessage({ type: 'error', text: msg.name + ' 를 넣지 못했다: ' + e.message }); }
    return;
  }
  if (msg.type === 'build') {
    try { await build(); }
    catch (e) { figma.ui.postMessage({ type: 'error', text: '보드를 그리다 멈췄다: ' + e.message }); }
  }
};
