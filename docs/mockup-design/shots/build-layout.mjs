/* ================================================================
   build-layout.mjs — 캡처 목록에서 보드 좌표표를 만들어낸다.

   입력  : manifest.json (capture-screens.mjs 가 만든 파일별 실제 높이)
   출력  : layout.json                        — 섹션·아이템 좌표 + 커넥터
           README.md                          — 사람이 읽는 배치 명세
           ../../../tools/figma-flow-board/layout-data.js — 플러그인이 읽는 같은 데이터

   좌표를 손으로 관리하면 화면이 늘 때마다 어긋난다. 배치 의도(어느 행에
   무엇을 어떤 순서로 두는가)만 SECTIONS 에 적고 나머지는 계산으로 뽑는다.
   ================================================================ */
import fs from 'fs';
import path from 'path';

const CANVAS = {
  screenWidth: 390,   // 캡처 폭 고정
  gapX: 260,          // 열 사이 통로 — 세로 화살표가 화면을 밟지 않고 지나가는 자리
  rowGap: 140,
  captionGap: 60,     // 이미지 아래 캡션이 차지하는 높이
  pad: 80,
  headerHeight: 190,  // 섹션 제목 + 설명이 차지하는 윗공간
  sectionGap: 300
};
const COL = CANVAS.screenWidth + CANVAS.gapX;          // 열 피치 650
const ROW_EXTRA = CANVAS.rowGap + CANVAS.captionGap;   // 행 사이 200

/* ── 배치 의도 ──────────────────────────────────────────────────
   rows 의 각 배열이 한 행이다. null 은 빈 칸 — 분기가 어느 열에서
   갈라지는지 보이게 하려고 일부러 비운다. ------------------------ */
const SECTIONS = [
  {
    id: 'auth', title: '[인증 · 온보딩 플로우]', fill: '#F5FBFF',
    note: '게스트는 탐색까지만 가능하고, 저장 시점에 로그인으로 유도된다. 가입은 약관→정보→이메일 인증→완료의 4스텝.',
    rows: [
      ['01-login', '02-signup-1', '03-signup-2', '04-signup-3', '05-signup-4'],
      [null, '06-findpw', '07-newpw']
    ]
  },
  {
    id: 'explore', title: '[대회 탐색 플로우]', fill: '#EBFFEE',
    note: '홈 히어로 CTA "이 대회로 동선 만들기"가 위저드 진입의 주 경로다. 캘린더는 리스트·월간 두 뷰에 필터 오버레이를 얹는다.',
    rows: [
      ['10-home', '12-calendar', '15-detail'],
      ['11-home-guest', '13-calendar-cal', '14-calendar-filter']
    ]
  },
  {
    id: 'wizard', title: '[여행 동선 위저드 → 결과]', fill: '#FFF7F0',
    note: '3스텝 위저드(일정 → 취향 → 숙소)를 거쳐 동선 결과로. 결과 화면에서 편집·POI 추가로 갈라졌다가 다시 결과로 돌아온다.',
    rows: [
      ['20-w1', '21-w2', '22-w3', '23-result', '24-result-edit', '25-result-poi']
    ]
  },
  {
    id: 'courses', title: '[러닝 코스 · GPS 기록]', fill: '#F8F5FF',
    note: '코스 상세에서 기록을 시작하면 심연(deep) 지면의 GPS 화면으로 전환되고, 종료 시 요약으로 넘어간다. 두루누비 코스가 없는 수도권은 걷기 좋은 곳이 기본 화면이 되며, 경로 없이 바로 기록을 시작한다.',
    rows: [
      ['30-courses', '32-coursedetail', '33-run', '34-runsum'],
      ['31-courses-region', '140-courses-seoul']
    ]
  },
  {
    id: 'library', title: '[보관함 → 상세 · 내 정보]', fill: '#FFFBF0',
    note: '저장한 동선·코스·찜한 대회가 모이는 종착점. 각 카드는 해당 상세 화면으로 되돌아 나가고, 상단 설정 아이콘이 계정 관리 진입점이다.',
    rows: [
      ['40-library', '41-library-course', '42-library-fav'],
      [null, '17-coursedetail-saved', '16-account']
    ]
  },
  {
    id: 'guest', title: '[게스트 · 로그인 유도와 복귀]', fill: '#F3F0FF',
    note: '게스트가 찜·저장을 누르면 동작별 문구의 로그인 모달이 뜬다. 로그인 후에는 원래 있던 화면으로 돌아오고, 저장·찜은 자동 실행하지 않는다 — 사용자가 다시 누른다.',
    rows: [
      ['72-guest-fav', '73-guest-route', '74-guest-course', '75-login-return']
    ]
  },
  {
    id: 'confirm', title: '[되돌릴 수 없는 동작 · 확인]', fill: '#FFF0F3',
    note: '삭제·탈퇴처럼 복구가 어려운 동작 앞에 확인 모달을 세운다. [취소]와 [삭제]/[탈퇴] 두 갈래.',
    rows: [
      ['100-confirm-route', '101-confirm-course', '102-confirm-run', '103-confirm-quit', '104-confirm-runsave']
    ]
  },
  {
    id: 'states', title: '[사용자 조작으로 도달하는 상태]', fill: '#F1FEFD',
    note: '버튼·탭·입력으로 도달하는 중간 상태들. 위 플로우의 각 화면에서 한 번의 조작으로 갈라져 나온다.',
    rows: [
      ['80-signup-1-agreed', '81-signup-3-filled', '82-findpw-sent', '83-newpw-changed'],
      ['84-calendar-selday', '85-calendar-nextmonth', '86-calendar-filtered', '87-calendar-noresult'],
      ['88-calendar-search', '89-w1-custom', '90-w2-none', '91-w3-picked'],
      ['92-w3-generating', '93-result-d1', '94-result-dplus1', '95-result-edit-rm'],
      ['96-courses-region-sel', '97-courses-longdist', '98-detail-closed', '99-detail-before']
    ]
  },
  {
    id: 'errors', title: '[예외 상태 — 로딩 · 빈 · 오류]', fill: '#FFF5F5',
    note: '목업의 STATE 디버그 레일로만 도달하는 예외 상태. 각 캡션 앞부분이 원래 소속 화면이다.',
    rows: [
      ['60-login-error', '61-detail-loading', '62-detail-empty', '63-w3-loading'],
      ['64-result-empty', '65-result-poi-swap', '70-library-empty', '71-coursedetail-ran'],
      ['66-courses-locating', '67-courses-nostart', '68-courses-nocourse', '69-courses-none']
    ]
  },
  {
    id: 'partial', title: '[영역별 상태 · 부분 실패]', fill: '#F5F5FF',
    note: '한 화면에서 여러 API를 부를 때 한쪽 실패가 화면 전체를 막지 않는다. 홈은 마감임박·축제가, 캘린더는 목록·날짜 수가 따로 실패한다. 오프라인에서는 마지막으로 받아온 데이터를 보여주고 수정 동작을 잠근다.',
    rows: [
      ['110-home-loading', '111-home-empty', '112-home-error', '116-home-offline'],
      ['113-home-fest-loading', '114-home-fest-empty', '115-home-fest-error'],
      ['120-calendar-loading', '121-calendar-error', '122-calendar-nodots'],
      ['123-calendar-searchnone', '124-calendar-dayempty', '125-calendar-monthempty'],
      ['130-library-error-route', '131-library-error-course', '132-library-error-fav', '133-library-offline']
    ]
  },
  {
    id: 'ds', title: '[디자인 시스템 v2]', fill: '#F9F9F9',
    note: '컬러·타이포·카테고리 태그 9종·상태 칩·고도 스트립 기준.',
    rows: [['50-sheet']]
  }
];

/* ── 커넥터 ────────────────────────────────────────────────────
   from/to 는 캡처 파일명(name). 같은 섹션 안이면 자동으로 'inner',
   섹션을 넘으면 'cross' 로 분류해 색·굵기를 달리한다. ------------ */
const CONNECTORS = [
  // 인증
  ['01-login', '02-signup-1', '회원가입'],
  ['02-signup-1', '03-signup-2', '약관 동의'],
  ['03-signup-2', '04-signup-3', '다음'],
  ['04-signup-3', '05-signup-4', '인증 확인'],
  ['01-login', '06-findpw', '비밀번호 찾기'],
  ['06-findpw', '07-newpw', '메일 링크'],
  ['07-newpw', '01-login', '재설정 완료'],
  ['05-signup-4', '10-home', '시작하기'],
  ['01-login', '10-home', '로그인'],
  ['01-login', '11-home-guest', '둘러보기 (게스트)'],
  ['11-home-guest', '01-login', '저장 시 로그인 유도'],

  // 대회 탐색
  ['10-home', '12-calendar', '탭 · 캘린더'],
  ['12-calendar', '13-calendar-cal', '월간 보기'],
  ['12-calendar', '14-calendar-filter', '필터'],
  ['10-home', '15-detail', '대회 보기'],
  ['12-calendar', '15-detail', '대회 선택'],

  // 위저드 → 결과
  ['10-home', '20-w1', '이 대회로 동선 만들기'],
  ['15-detail', '20-w1', '동선 만들기'],
  ['20-w1', '21-w2', '일정 입력'],
  ['21-w2', '22-w3', '취향 선택'],
  ['22-w3', '23-result', '숙소 선택'],
  ['23-result', '24-result-edit', '편집'],
  ['24-result-edit', '25-result-poi', '장소 추가'],
  ['25-result-poi', '23-result', '추가 완료'],
  ['23-result', '40-library', '이 동선 저장하기'],

  // 러닝 코스 · GPS
  ['10-home', '30-courses', '탭 · 러닝코스'],
  ['30-courses', '32-coursedetail', '코스 선택'],
  ['30-courses', '31-courses-region', '지역 탭'],
  ['30-courses', '140-courses-seoul', '출발지 · 서울시청'],
  ['140-courses-seoul', '33-run', '여기서 뛰기'],
  ['32-coursedetail', '33-run', '기록 시작'],
  ['33-run', '34-runsum', '종료'],
  ['34-runsum', '40-library', '기록 저장'],

  // 보관함 내부
  ['40-library', '41-library-course', '코스 탭'],
  ['40-library', '42-library-fav', '즐겨찾기 탭'],

  // ── 피드백 1 · 보관함 카드에서 상세 화면으로 되돌아 나간다 ──
  ['40-library', '23-result', '동선 카드 선택'],
  ['41-library-course', '32-coursedetail', '저장 코스 선택'],
  ['41-library-course', '71-coursedetail-ran', '러닝 기록 선택'],
  ['42-library-fav', '15-detail', '찜한 대회 선택'],

  // ── 피드백 2 · 코스 저장 ──
  ['32-coursedetail', '17-coursedetail-saved', '코스 저장'],
  ['17-coursedetail-saved', '41-library-course', '보관함에서 확인'],
  ['32-coursedetail', '74-guest-course', '코스 저장 (게스트)'],

  // ── 피드백 3 · 내 정보 · 계정 관리 ──
  ['40-library', '16-account', '설정 아이콘'],
  ['16-account', '07-newpw', '비밀번호 변경'],
  ['16-account', '01-login', '로그아웃'],
  ['16-account', '103-confirm-quit', '회원 탈퇴'],
  ['103-confirm-quit', '01-login', '탈퇴 완료'],

  // ── 피드백 4 · 게스트 로그인 유도와 복귀 ──
  ['12-calendar', '72-guest-fav', '찜 (게스트)'],
  ['23-result', '73-guest-route', '동선 저장 (게스트)'],
  ['72-guest-fav', '01-login', '로그인하기'],
  ['73-guest-route', '01-login', '로그인하기'],
  ['74-guest-course', '01-login', '로그인하기'],
  ['01-login', '75-login-return', '로그인 성공'],
  ['75-login-return', '23-result', '사용자가 다시 저장'],

  // ── 피드백 5 · 삭제 · 탈퇴 확인 ──
  ['40-library', '100-confirm-route', '동선 삭제'],
  ['41-library-course', '101-confirm-course', '코스 삭제'],
  ['41-library-course', '102-confirm-run', '기록 삭제'],
  ['34-runsum', '104-confirm-runsave', '저장 안 하고 나가기'],
  ['100-confirm-route', '70-library-empty', '삭제'],

  // ── 피드백 1~3 · 오류에서 되돌아오기 ──
  ['112-home-error', '10-home', '다시 시도'],
  ['121-calendar-error', '12-calendar', '다시 시도'],
  ['130-library-error-route', '40-library', '다시 시도'],
  ['116-home-offline', '10-home', '새로고침']
];

/* ================================================================
   계산
   ================================================================ */
const manifest = JSON.parse(fs.readFileSync('./manifest.json', 'utf8'));
const byName = Object.fromEntries(manifest.map(m => [m.name, m]));

const missing = [];
const placed = new Set();
for (const sec of SECTIONS)
  for (const row of sec.rows)
    for (const n of row)
      if (n){ placed.add(n); if (!byName[n]) missing.push(n); }
if (missing.length){
  console.error('manifest 에 없는 캡처가 배치돼 있다:', missing.join(', '));
  process.exit(1);
}
const orphan = manifest.map(m => m.name).filter(n => !placed.has(n));
if (orphan.length) console.warn('경고 · 어느 섹션에도 배치되지 않은 캡처:', orphan.join(', '));

const items = {};            // name -> 보드 절대 좌표
let boardY = 0;
const sections = SECTIONS.map(sec => {
  const cols = Math.max(...sec.rows.map(r => r.length));
  const w = CANVAS.pad * 2 + cols * CANVAS.screenWidth + (cols - 1) * CANVAS.gapX;
  let y = CANVAS.headerHeight;
  const out = [], rowTops = [], rowBottoms = [];
  sec.rows.forEach((row, ri) => {
    const rowH = Math.max(...row.filter(Boolean).map(n => byName[n].h));
    rowTops.push(y); rowBottoms.push(y + rowH);
    row.forEach((n, ci) => {
      if (!n) return;
      const m = byName[n];
      const it = { name: n, label: m.label, file: m.file, row: ri, col: ci,
        x: CANVAS.pad + ci * COL, y, w: m.w || CANVAS.screenWidth, h: m.h };
      out.push(it);
      items[n] = { ...it, boardX: 0 + it.x, boardY: boardY + it.y, section: sec.id };
    });
    if (ri < sec.rows.length - 1) y += rowH + ROW_EXTRA;
    else y += rowH;
  });
  const h = y + CANVAS.rowGap;
  const s = { id: sec.id, title: sec.title, fill: sec.fill, note: sec.note, x: 0, y: boardY, w, h, rowTops, rowBottoms, items: out };
  boardY += h + CANVAS.sectionGap;
  return s;
});

const secOf = n => items[n].section;
const connectors = CONNECTORS.map(([from, to, label]) => {
  if (!items[from] || !items[to]) throw new Error('커넥터가 없는 화면을 가리킨다: ' + from + ' → ' + to);
  return { from, to, label, kind: secOf(from) === secOf(to) ? 'inner' : 'cross' };
});

const layout = { canvas: CANVAS, style: {
  inner: { color: '#3DADFF', weight: 3, fontSize: 20 },
  cross: { color: '#874FFF', weight: 5, fontSize: 24 }
}, sections, connectors };

fs.writeFileSync('./layout.json', JSON.stringify(layout, null, 2));

/* Figma 플러그인의 code.js 를 여기서 함께 만든다.
   플러그인은 파일을 import 할 수 없어서 좌표 데이터를 코드에 박아야 한다 —
   손으로 옮겨 붙이면 반드시 어긋나므로 템플릿 앞에 데이터를 붙여 통째로 써낸다. */
const pluginDir = path.resolve('../../../tools/figma-flow-board');
fs.mkdirSync(pluginDir, { recursive: true });
const tmplPath = path.join(pluginDir, 'code.template.js');
const routerPath = path.join(pluginDir, 'router.js');
if (fs.existsSync(tmplPath) && fs.existsSync(routerPath)){
  fs.writeFileSync(path.join(pluginDir, 'code.js'),
    '/* 자동 생성 — docs/mockup-design/shots/build-layout.mjs 가 만든다.\n' +
    '   직접 고치지 말 것. 좌표는 build-layout.mjs 의 SECTIONS/CONNECTORS,\n' +
    '   라우팅은 router.js, 나머지 로직은 code.template.js 에 있다. */\n' +
    'const LAYOUT = ' + JSON.stringify(layout) + ';\n\n' +
    fs.readFileSync(routerPath, 'utf8') + '\n' +
    fs.readFileSync(tmplPath, 'utf8'));
} else {
  console.warn('경고 · code.template.js 또는 router.js 가 없어 플러그인 code.js 를 만들지 못했다');
}

/* ── README ── */
const nl = '\n';
let md = `# 런닝구 UI MOCKUP V2 — 화면 플로우 배치 명세

이 파일과 \`layout.json\` 은 **\`build-layout.mjs\` 가 만들어낸다.** 손으로 고치지 말고
배치를 바꾸려면 \`build-layout.mjs\` 의 \`SECTIONS\` · \`CONNECTORS\` 를 고친 뒤 다시 돌린다.

\`png/\` 의 ${manifest.length}장을 Figma 보드에 옮기기 위한 좌표표다.
모든 이미지는 폭 **390px** 고정, 높이는 화면마다 다르다 (전체 스크롤 캡처라 세로가 길다).
좌표는 **각 섹션 왼쪽 위 (0,0) 기준 로컬 좌표**이며, 섹션 자체의 보드 좌표는 \`x=0, y=섹션Y\`다.

캡션 텍스트는 각 이미지 바로 아래 \`y = 이미지Y + 이미지높이 + 14\`, 24px 크기로 둔다.
${nl}`;
for (const s of sections){
  md += `${nl}## ${s.title}${nl}`;
  md += `섹션 보드 좌표 \`x=${s.x}, y=${s.y}\` · 크기 \`${s.w} × ${s.h}\` · 배경 \`${s.fill}\`  ${nl}`;
  md += `설명: ${s.note}${nl}${nl}`;
  md += `| 파일 | 캡션 | x | y | 크기 |${nl}|---|---|---|---|---|${nl}`;
  for (const it of s.items) md += `| \`${it.name}.png\` | ${it.label} | ${it.x} | ${it.y} | ${it.w} × ${it.h} |${nl}`;
}
md += `${nl}## 화살표 (커넥터)${nl}`;
for (const [kind, cap] of [['inner', '섹션 내부 — 파랑 `#3DADFF`, 굵기 3, 라벨 20px'], ['cross', '섹션 간 — 보라 `#874FFF`, 굵기 5, 라벨 24px']]){
  md += `${nl}### ${cap}${nl}${nl}| 출발 | 도착 | 라벨 |${nl}|---|---|---|${nl}`;
  for (const c of connectors.filter(c => c.kind === kind))
    md += `| ${items[c.from].label} | ${items[c.to].label} | ${c.label} |${nl}`;
}
md += `${nl}0열끼리 잇는 세로 화살표는 열 사이 통로(폭 ${CANVAS.gapX}px)로 라우팅된다.
플러그인이 양끝을 오른쪽으로 빼서 꺾은선을 그리므로 화면 위를 지나지 않는다.

## 캡처 · 좌표 갱신 방법

\`\`\`bash
npm i playwright && npx playwright install chromium
node capture-screens.mjs   # png/ 와 manifest.json 을 다시 만든다
node build-layout.mjs      # layout.json · README.md · 플러그인 layout-data.js 를 다시 만든다
\`\`\`
\`capture-screens.mjs\` 안의 \`SHOTS\` 표가 화면 목록이다. 각 행은 \`[파일명, 진입 해시, 진입 후 실행할 JS, 캡션]\`.
오버레이(바텀시트·모달)가 떠 있으면 기기 프레임 그대로, 아니면 스크롤 콘텐츠 전체가 보이도록 폰을 늘려서 찍는다.

보드를 실제로 그리는 건 \`tools/figma-flow-board\` Figma 플러그인이다. Figma MCP 는 쓰지 않는다
(Starter 플랜 월 20회 한도로는 ${manifest.length}장을 옮길 수 없다).
`;
fs.writeFileSync('./README.md', md);

const totalH = boardY - CANVAS.sectionGap;
console.log(`섹션 ${sections.length} · 화면 ${manifest.length} · 커넥터 ${connectors.length}` +
  ` (섹션내부 ${connectors.filter(c => c.kind === 'inner').length} / 섹션간 ${connectors.filter(c => c.kind === 'cross').length})`);
console.log(`보드 크기 ${Math.max(...sections.map(s => s.w))} × ${totalH}`);
console.log('wrote layout.json · README.md · ' + path.join(pluginDir, 'code.js'));
