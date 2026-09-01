import { chromium } from 'playwright';
import { fileURLToPath, pathToFileURL } from 'url';
import fs from 'fs';
import path from 'path';

/* 이 스크립트 위치에서 찾는다. 절대경로를 박아 두면 그 PC 에서만 돈다 — 실제로
   `C:/git/RunningGu/...` 가 박혀 있어 다른 기기에서는 ERR_FILE_NOT_FOUND 였다 (#215). */
const MOCKUP = fileURLToPath(new URL('../런닝구-목업-v2.html', import.meta.url));
const OUT = './png';   // 캡처는 곧바로 png/ 에 떨어진다 (예전엔 임시폴더에 찍고 손으로 옮겼다)
fs.mkdirSync(OUT, { recursive: true });

// [파일명, 진입 해시, 진입 후 실행할 셋업 JS(없으면 ''), 라벨, 추가 대기(ms)]
const SHOTS = [
  // ── 기본 화면 ──────────────────────────────────────────────
  ['01-login',            'login',          '', '로그인'],
  ['02-signup-1',         'signup-1',       '', '회원가입 1 · 약관 동의'],
  ['03-signup-2',         'signup-2',       '', '회원가입 2 · 정보 입력'],
  ['04-signup-3',         'signup-3',       '', '회원가입 3 · 이메일 인증'],
  ['05-signup-4',         'signup-4',       '', '회원가입 4 · 가입 완료'],
  ['06-findpw',           'findpw',         '', '비밀번호 찾기'],
  ['07-newpw',            'newpw',          '', '비밀번호 재설정'],
  ['10-home',             'home',           '', '홈'],
  ['11-home-guest',       'home-guest',     '', '홈 · 게스트 로그인 유도'],
  ['12-calendar',         'calendar',       '', '캘린더 · 리스트'],
  ['13-calendar-cal',     'calendar-cal',   '', '캘린더 · 월간'],
  ['14-calendar-filter',  'calendar-filter','', '캘린더 · 필터 시트'],
  ['15-detail',           'detail',         '', '대회 상세'],
  ['20-w1',               'w1',             '', '위저드 1 · 언제 다녀올까요'],
  ['21-w2',               'w2',             '', '위저드 2 · 어떻게 뛰고 뭘 좋아하세요'],
  ['22-w3',               'w3',             '', '위저드 3 · 어디서 묵을까요'],
  ['23-result',           'result',         '', '동선 결과'],
  ['24-result-edit',      'result-edit',    '', '동선 결과 · 편집 모드'],
  ['25-result-poi',       'result-poi',     '', '동선 결과 · POI 추가 시트'],
  ['30-courses',          'courses',        '', '러닝 코스 · 출발지 주변'],
  ['31-courses-region',   'courses-region', '', '러닝 코스 · 지역별'],
  ['32-coursedetail',     'courses',        "APP.openCourseDetail()", '코스 상세'],
  ['40-library',          'library',        '', '보관함 · 동선'],
  ['41-library-course',   'library-course', '', '보관함 · 러닝코스'],
  ['42-library-fav',      'library-fav',    '', '보관함 · 찜한 대회'],
  ['50-sheet',            'sheet',          '', '디자인 시스템 v2'],

  // ── 예외 상태 (STATE 디버그 레일) ───────────────────────────
  ['60-login-error',      'login',   "APP.dbgLogin('error')",       '로그인 · 오류'],
  ['61-detail-loading',   'detail',  "APP.setFestState('loading')", '대회 상세 · 인근 축제 로딩'],
  ['62-detail-empty',     'detail',  "APP.setFestState('empty')",   '대회 상세 · 인근 축제 없음'],
  ['63-w3-loading',       'w3',      "APP.dbgW3('loading')",        '위저드 3 · 숙소 로딩'],
  ['64-result-empty',     'result',  "APP.dbgResult('empty')",      '동선 결과 · 빈 상태'],
  ['65-result-poi-swap',  'result',  "APP.openSwap(1,'관광지')",     '동선 결과 · POI 교체 시트'],
  ['67-courses-nostart',  'courses', "APP.dbgCourses('nostart')",   '러닝 코스 · 출발지 없음'],
  ['68-courses-nocourse', 'courses', "APP.dbgCourses('nocourse')",  '러닝 코스 · 코스 0'],
  ['69-courses-none',     'courses', "APP.dbgCourses('none')",      '러닝 코스 · 결과 0건'],
  ['70-library-empty',    'library', "APP.dbgLib('empty')",         '보관함 · 빈 상태'],

  // ── 사용자가 눌러서 도달하는 상태 ───────────────────────────
  ['80-signup-1-agreed',   'signup-1', "APP.toggleAllAgree()",              '회원가입 1 · 전체 동의 (다음 활성)'],
  ['81-signup-3-filled',   'signup-3', "APP.setCode('482913')",             '회원가입 3 · 인증코드 입력 완료'],
  ['82-findpw-sent',       'findpw',   "APP.sendReset()",                   '비밀번호 찾기 · 링크 발송 완료', 2600],
  ['83-newpw-changed',     'newpw',    "APP.changePw()",                    '비밀번호 재설정 · 변경 완료'],
  ['84-calendar-selday',   'calendar-cal',
    "[...document.querySelectorAll('button[onclick*=\"selDate\"]')].find(x=>x.children.length>1).click()",
    '캘린더 · 대회 있는 날짜 선택'],
  ['85-calendar-nextmonth','calendar-cal', "APP.nextMonth()",               '캘린더 · 다음 달'],
  ['86-calendar-filtered', 'calendar',
    "APP.openFilter();APP.toggleDraft('events','10K');APP.toggleDraft('regions','세종');APP.applyFilter()",
    '캘린더 · 필터 적용 (칩 노출)'],
  ['87-calendar-noresult', 'calendar',
    "APP.openFilter();APP.toggleDraft('events','풀');APP.toggleDraft('regions','제주');APP.applyFilter()",
    '캘린더 · 조건에 맞는 대회 0건'],
  ['88-calendar-search',   'calendar',
    "document.querySelector('#calQ').value='부산';APP.setQ('부산')",
    '캘린더 · 검색 결과'],
  ['89-w1-custom',         'w1',  "APP.pickPattern('custom')",              '위저드 1 · 직접 날짜 선택'],
  ['90-w2-none',           'w2',  "APP.togglePref('관광지');APP.togglePref('맛집')", '위저드 2 · 미선택 (다음 비활성)'],
  ['91-w3-picked',         'w3',  "APP.setHotel('호텔 세종 가온')",          '위저드 3 · 숙소 선택됨'],
  ['92-w3-generating',     'w3',
    "window.setTimeout=function(){return 0};APP.setHotel('호텔 세종 가온');APP.wizardNext()",
    '위저드 3 · 동선 생성 중'],
  ['93-result-d1',         'result', "APP.setDay(0)",                       '동선 결과 · D-1'],
  ['94-result-dplus1',     'result', "APP.setDay(2)",                       '동선 결과 · D+1 (회복 모드)'],
  ['95-result-edit-rm',    'result-edit', "APP.removeBlock(1)",             '동선 결과 · 편집 · 블록 삭제 후'],
  ['96-courses-region-sel','courses-region',
    "document.querySelector('button[onclick*=\"setCRegion\"]').click()",
    '러닝 코스 · 지역 선택'],
  ['97-courses-longdist',  'courses', "APP.setDist(20)",                    '러닝 코스 · 목표 20km (코스 짧음 안내)'],
  ['98-detail-closed',     'calendar',"APP.openDetail('jeonju')",           '대회 상세 · 접수 마감'],
  ['99-detail-before',     'calendar',"APP.openDetail('chuncheon')",        '대회 상세 · 접수 전'],

  // ── 피드백 반영 · 필수 보완 ─────────────────────────────────
  ['16-account',           'account', '',                                   '내 정보 · 계정 관리'],
  ['17-coursedetail-saved','courses', "APP.openCourseDetail();APP.saveCourse()", '코스 상세 · 보관함에 저장'],
  ['72-guest-fav',         'calendar',"APP.doGuest();APP.go('calendar');APP.toggleFav('sejong')", '게스트 · 찜 시도 (로그인 유도)'],
  ['73-guest-route',       'result',  "APP.doGuest();APP.go('result');APP.saveRoute()",           '게스트 · 동선 저장 시도 (로그인 유도)'],
  ['74-guest-course',      'courses', "APP.doGuest();APP.openCourseDetail();APP.saveCourse()", '게스트 · 코스 저장 시도 (로그인 유도)'],
  ['75-login-return',      'result',  "APP.doGuest();APP.go('result');APP.saveRoute();APP.goLoginFromGuest();APP.doLogin()", '로그인 후 원래 화면 복귀'],

  // ── 피드백 반영 · 삭제·탈퇴 확인 모달 ───────────────────────
  ['100-confirm-route',    'library',       "APP.askDelete('route')",   '확인 · 동선 삭제'],
  ['101-confirm-course',   'library-course',"APP.askDelete('course')",  '확인 · 저장 코스 삭제'],
  ['103-confirm-quit',     'account',       "APP.askDelete('quit')",    '확인 · 회원 탈퇴'],

  // ── 피드백 반영 · 홈 영역별 상태 ────────────────────────────
  ['110-home-loading',     'home', "APP.dbgHome('loading')",  '홈 · 마감 임박 로딩'],
  ['111-home-empty',       'home', "APP.dbgHome('empty')",    '홈 · 접수 중 대회 없음'],
  ['112-home-error',       'home', "APP.dbgHome('error')",    '홈 · 마감 임박 조회 실패'],
  ['113-home-fest-loading','home', "APP.dbgFest('loading')",  '홈 · 축제 로딩'],
  ['114-home-fest-empty',  'home', "APP.dbgFest('empty')",    '홈 · 추천 축제 없음'],
  ['115-home-fest-error',  'home', "APP.dbgFest('error')",    '홈 · 축제만 실패 (부분 실패)'],
  ['116-home-offline',     'home', "APP.setOffline(true)",    '홈 · 오프라인'],

  // ── 피드백 반영 · 캘린더 상태 ───────────────────────────────
  ['120-calendar-loading',    'calendar',    "APP.dbgCal('loading')", '캘린더 · 로딩'],
  ['121-calendar-error',      'calendar',    "APP.dbgCal('error')",   '캘린더 · 조회 실패'],
  ['122-calendar-nodots',     'calendar-cal',"APP.dbgCal('nodots')",  '캘린더 · 날짜 수만 실패 (부분 실패)'],
  ['123-calendar-searchnone', 'calendar',
    "document.querySelector('#calQ').value='없는대회명';APP.setQ('없는대회명')",
    '캘린더 · 검색 결과 없음'],
  ['124-calendar-dayempty',   'calendar-cal',"APP.selDate('2026-08-05')", '캘린더 · 이 날에는 대회 없음'],
  ['125-calendar-monthempty', 'calendar-cal',"APP.nextMonth();APP.nextMonth();APP.nextMonth()", '캘린더 · 이 달에는 대회 없음'],

  // ── 피드백 반영 · 보관함 오류 · 오프라인 ────────────────────
  ['130-library-error-route', 'library',       "APP.dbgLib('error')", '보관함 · 동선 조회 실패'],
  ['131-library-error-course','library-course',"APP.dbgLib('error')", '보관함 · 러닝코스 조회 실패'],
  ['132-library-error-fav',   'library-fav',   "APP.dbgLib('error')", '보관함 · 찜한 대회 조회 실패'],
  ['140-courses-seoul',      'courses', "APP.setCourseStart('서울시청')", '러닝 코스 · 서울 (걷기 좋은 곳)'],
  ['133-library-offline',     'library',       "APP.setOffline(true)",'보관함 · 오프라인 (수정 비활성)']
];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 900, height: 1000 }, deviceScaleFactor: 2 });

const manifest = [];
for (const [name, hash, setup, label, extraWait] of SHOTS) {
  await page.goto(pathToFileURL(MOCKUP).href + '#' + hash + '!s', { waitUntil: 'networkidle' });
  await page.reload({ waitUntil: 'networkidle' });
  await page.addStyleTag({ content: '#debugHost{display:none!important}' });
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(600);

  if (setup) {
    await page.evaluate(s => { eval(s); }, setup);
    await page.waitForTimeout(extraWait || 1100);
  } else {
    await page.waitForTimeout(900);
  }

  // 오버레이(바텀시트·모달·토스트)가 떠 있으면 기기 프레임 그대로,
  // 아니면 스크롤 콘텐츠 전체가 보이도록 폰을 세로로 늘려서 찍는다
  const mode = await page.evaluate(() => {
    const vp = document.querySelector('#viewport');
    const phone = document.querySelector('.phone');
    const hasOverlay = document.querySelector('#overlayHost').children.length > 0;
    if (hasOverlay || vp.scrollHeight <= vp.clientHeight + 4) return 'frame';
    vp.style.overflow = 'visible';
    vp.style.flex = 'none';
    vp.style.height = vp.scrollHeight + 'px';
    phone.style.height = 'auto';
    return 'full';
  });
  await page.waitForTimeout(500);

  const el = await page.$('.phone');
  const box = await el.boundingBox();
  const file = path.join(OUT, name + '.png');
  await el.screenshot({ path: file });
  manifest.push({ name, hash, setup, label, mode, w: Math.round(box.width), h: Math.round(box.height), file: 'png/' + name + '.png' });
  console.log('OK', name, mode, Math.round(box.height) + 'px', label);
}
fs.writeFileSync('./manifest.json', JSON.stringify(manifest, null, 2));
await browser.close();
console.log('DONE', manifest.length);
