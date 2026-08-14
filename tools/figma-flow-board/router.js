/* ================================================================
   커넥터 라우터 — 플러그인과 검산 스크립트가 같이 쓴다.

   보드가 격자라는 성질을 이용한다. 모든 섹션이 같은 열 피치(650px)를
   쓰고 화면 폭은 390px 이라, 열 사이 통로(폭 260px)는 보드 전체 높이에서
   비어 있다. 그래서 세로 이동은 무조건 통로로 한다.

   가로 이동은 행과 행 사이, 섹션과 섹션 사이의 빈 띠에서만 한다. 그 띠는
   해당 섹션의 모든 열에 걸쳐 비어 있고, 섹션 폭 바깥은 애초에 아무것도
   없으므로 어디까지 가로질러도 안전하다.

   경로는 항상 "오른쪽으로 빠져나가 통로를 타고 내려가거나 올라가서,
   대상 옆 통로로 갈아탄 뒤 대상의 옆구리로 들어간다".
   ================================================================ */

var GUTTER = 130;      // 화면 가장자리에서 통로 한가운데까지
var ANCHOR_CAP = 900;  // 세로로 아주 긴 캡처는 위쪽에서 앵커를 잡는다

function rgAnchorY(r) { return r.y + Math.min(r.h, ANCHOR_CAP) / 2; }

/* layout.json → 커넥터가 쓰는 절대 좌표 사각형 + 그 화면이 쓸 가로 통로 */
function rgBuildRects(L) {
  var R = {};
  for (var i = 0; i < L.sections.length; i++) {
    var s = L.sections[i];
    var last = s.rowTops.length - 1;
    for (var k = 0; k < s.items.length; k++) {
      var it = s.items[k];
      R[it.name] = {
        x: s.x + it.x, y: s.y + it.y, w: it.w, h: it.h, label: it.label,
        // 이 화면에서 위로 빠질 때 / 아래로 빠질 때 쓸 가로 통로의 y
        cAbove: it.row > 0 ? s.y + s.rowTops[it.row] - 78 : s.y - 150,
        cBelow: it.row < last ? s.y + s.rowBottoms[it.row] + 122 : s.y + s.h + 150
      };
    }
  }
  return R;
}

function rgDedupe(pts) {
  var out = [];
  for (var i = 0; i < pts.length; i++) {
    var p = pts[i], q = out[out.length - 1];
    if (q && Math.abs(p.x - q.x) < 1 && Math.abs(p.y - q.y) < 1) continue;
    out.push(p);
  }
  // 일직선으로 이어지는 가운데 점은 지운다
  var trimmed = [out[0]];
  for (var j = 1; j < out.length - 1; j++) {
    var a = trimmed[trimmed.length - 1], b = out[j], c = out[j + 1];
    var straight = (Math.abs(a.x - b.x) < 1 && Math.abs(b.x - c.x) < 1) ||
                   (Math.abs(a.y - b.y) < 1 && Math.abs(b.y - c.y) < 1);
    if (!straight) trimmed.push(b);
  }
  if (out.length > 1) trimmed.push(out[out.length - 1]);
  return trimmed;
}

/* seed 는 커넥터 번호 — 같은 통로에 여러 선이 겹쳐 붙지 않게 조금씩 어긋낸다 */
function rgRoute(a, b, seed) {
  var ay = rgAnchorY(a), by = rgAnchorY(b);
  var goRight = b.x > a.x;
  var vxA = a.x + a.w + GUTTER;                          // 출발은 언제나 오른쪽
  var vxB = goRight ? b.x - GUTTER : b.x + b.w + GUTTER; // 도착은 가까운 쪽 옆구리
  var entryX = goRight ? b.x : b.x + b.w;
  var j = ((seed % 5) - 2) * 26;

  if (Math.abs(vxA - vxB) < 1) {
    var vx = vxA + j;
    return rgDedupe([
      { x: a.x + a.w, y: ay }, { x: vx, y: ay }, { x: vx, y: by }, { x: entryX, y: by }
    ]);
  }
  var hy = (by < ay ? a.cAbove : a.cBelow) + ((seed % 4) - 1.5) * 24;
  return rgDedupe([
    { x: a.x + a.w, y: ay },
    { x: vxA + j, y: ay },
    { x: vxA + j, y: hy },
    { x: vxB + j, y: hy },
    { x: vxB + j, y: by },
    { x: entryX, y: by }
  ]);
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { rgRoute: rgRoute, rgBuildRects: rgBuildRects, rgAnchorY: rgAnchorY };
}
