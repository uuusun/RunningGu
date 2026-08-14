/* 커넥터 검산 — 플러그인이 실제로 쓰는 router.js 를 그대로 불러
   화살표가 다른 화면 위를 밟는지 Figma 를 열기 전에 확인한다. */
import fs from 'fs';
import { createRequire } from 'module';

const require = createRequire(import.meta.url);
const { rgRoute, rgBuildRects } = require('../../../tools/figma-flow-board/router.js');

const L = JSON.parse(fs.readFileSync('./layout.json', 'utf8'));
const rects = rgBuildRects(L);

/* 축에 나란한 선분이 사각형 안을 지나는지 (양끝 화면은 제외) */
function hits(p0, p1, r){
  const x1 = Math.min(p0.x, p1.x), x2 = Math.max(p0.x, p1.x);
  const y1 = Math.min(p0.y, p1.y), y2 = Math.max(p0.y, p1.y);
  return x2 > r.x + 2 && x1 < r.x + r.w - 2 && y2 > r.y + 2 && y1 < r.y + r.h - 2;
}

let bad = 0, maxSeg = 0;
L.connectors.forEach((c, i) => {
  const a = rects[c.from], b = rects[c.to];
  const pts = rgRoute(a, b, i);
  maxSeg = Math.max(maxSeg, pts.length - 1);
  const over = new Set();
  for (let k = 1; k < pts.length; k++)
    for (const name in rects)
      if (name !== c.from && name !== c.to && hits(pts[k - 1], pts[k], rects[name])) over.add(name);
  if (over.size){
    bad++;
    console.log(`✗ ${a.label} → ${b.label} (${c.label})  밟는 화면: ${[...over].join(', ')}`);
  }
});
console.log(bad === 0
  ? `커넥터 ${L.connectors.length}개 전부 화면을 피해 간다 (최대 ${maxSeg}구간)`
  : `커넥터 ${L.connectors.length}개 중 ${bad}개가 화면 위를 지난다`);
process.exit(bad === 0 ? 0 : 1);
