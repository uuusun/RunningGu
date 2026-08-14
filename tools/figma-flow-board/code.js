/* 자동 생성 — docs/mockup-design/shots/build-layout.mjs 가 만든다.
   직접 고치지 말 것. 좌표는 build-layout.mjs 의 SECTIONS/CONNECTORS,
   라우팅은 router.js, 나머지 로직은 code.template.js 에 있다. */
const LAYOUT = {"canvas":{"screenWidth":390,"gapX":260,"rowGap":140,"captionGap":60,"pad":80,"headerHeight":190,"sectionGap":300},"style":{"inner":{"color":"#3DADFF","weight":3,"fontSize":20},"cross":{"color":"#874FFF","weight":5,"fontSize":24}},"sections":[{"id":"auth","title":"[인증 · 온보딩 플로우]","fill":"#F5FBFF","note":"게스트는 탐색까지만 가능하고, 저장 시점에 로그인으로 유도된다. 가입은 약관→정보→이메일 인증→완료의 4스텝.","x":0,"y":0,"w":3150,"h":2218,"rowTops":[190,1234],"rowBottoms":[1034,2078],"items":[{"name":"01-login","label":"로그인","file":"png/01-login.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":844},{"name":"02-signup-1","label":"회원가입 1 · 약관 동의","file":"png/02-signup-1.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":844},{"name":"03-signup-2","label":"회원가입 2 · 정보 입력","file":"png/03-signup-2.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":844},{"name":"04-signup-3","label":"회원가입 3 · 이메일 인증","file":"png/04-signup-3.png","row":0,"col":3,"x":2030,"y":190,"w":390,"h":844},{"name":"05-signup-4","label":"회원가입 4 · 가입 완료","file":"png/05-signup-4.png","row":0,"col":4,"x":2680,"y":190,"w":390,"h":844},{"name":"06-findpw","label":"비밀번호 찾기","file":"png/06-findpw.png","row":1,"col":1,"x":730,"y":1234,"w":390,"h":844},{"name":"07-newpw","label":"비밀번호 재설정","file":"png/07-newpw.png","row":1,"col":2,"x":1380,"y":1234,"w":390,"h":844}]},{"id":"explore","title":"[대회 탐색 플로우]","fill":"#EBFFEE","note":"홈 히어로 CTA \"이 대회로 동선 만들기\"가 위저드 진입의 주 경로다. 캘린더는 리스트·월간 두 뷰에 필터 오버레이를 얹는다.","x":0,"y":2518,"w":1850,"h":3137,"rowTops":[190,1777],"rowBottoms":[1577,2997],"items":[{"name":"10-home","label":"홈","file":"png/10-home.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":1128},{"name":"12-calendar","label":"캘린더 · 리스트","file":"png/12-calendar.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":1387},{"name":"15-detail","label":"대회 상세","file":"png/15-detail.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":1089},{"name":"11-home-guest","label":"홈 · 게스트 로그인 유도","file":"png/11-home-guest.png","row":1,"col":0,"x":80,"y":1777,"w":390,"h":844},{"name":"13-calendar-cal","label":"캘린더 · 월간","file":"png/13-calendar-cal.png","row":1,"col":1,"x":730,"y":1777,"w":390,"h":1220},{"name":"14-calendar-filter","label":"캘린더 · 필터 시트","file":"png/14-calendar-filter.png","row":1,"col":2,"x":1380,"y":1777,"w":390,"h":844}]},{"id":"wizard","title":"[여행 동선 위저드 → 결과]","fill":"#FFF7F0","note":"3스텝 위저드(일정 → 취향 → 숙소)를 거쳐 동선 결과로. 결과 화면에서 편집·POI 추가로 갈라졌다가 다시 결과로 돌아온다.","x":0,"y":5955,"w":3800,"h":1568,"rowTops":[190],"rowBottoms":[1428],"items":[{"name":"20-w1","label":"위저드 1 · 언제 다녀올까요","file":"png/20-w1.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":1073},{"name":"21-w2","label":"위저드 2 · 어떻게 뛰고 뭘 좋아하세요","file":"png/21-w2.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":844},{"name":"22-w3","label":"위저드 3 · 어디서 묵을까요","file":"png/22-w3.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":1089},{"name":"23-result","label":"동선 결과","file":"png/23-result.png","row":0,"col":3,"x":2030,"y":190,"w":390,"h":1238},{"name":"24-result-edit","label":"동선 결과 · 편집 모드","file":"png/24-result-edit.png","row":0,"col":4,"x":2680,"y":190,"w":390,"h":1055},{"name":"25-result-poi","label":"동선 결과 · POI 추가 시트","file":"png/25-result-poi.png","row":0,"col":5,"x":3330,"y":190,"w":390,"h":844}]},{"id":"courses","title":"[러닝 코스 · GPS 기록]","fill":"#F8F5FF","note":"코스 상세에서 기록을 시작하면 심연(deep) 지면의 GPS 화면으로 전환되고, 종료 시 요약으로 넘어간다. 두루누비 코스가 없는 수도권은 걷기 좋은 곳이 기본 화면이 되며, 경로 없이 바로 기록을 시작한다.","x":0,"y":7823,"w":2500,"h":3414,"rowTops":[190,2025],"rowBottoms":[1825,3274],"items":[{"name":"30-courses","label":"러닝 코스 · 내 주변","file":"png/30-courses.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":1635},{"name":"32-coursedetail","label":"코스 상세","file":"png/32-coursedetail.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":844},{"name":"33-run","label":"GPS 기록 중","file":"png/33-run.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":844},{"name":"34-runsum","label":"러닝 요약","file":"png/34-runsum.png","row":0,"col":3,"x":2030,"y":190,"w":390,"h":844},{"name":"31-courses-region","label":"러닝 코스 · 지역별","file":"png/31-courses-region.png","row":1,"col":0,"x":80,"y":2025,"w":390,"h":889},{"name":"140-courses-seoul","label":"러닝 코스 · 서울 (걷기 좋은 곳)","file":"png/140-courses-seoul.png","row":1,"col":1,"x":730,"y":2025,"w":390,"h":1249}]},{"id":"library","title":"[보관함 → 상세 · 내 정보]","fill":"#FFFBF0","note":"저장한 동선·코스·찜한 대회가 모이는 종착점. 각 카드는 해당 상세 화면으로 되돌아 나가고, 상단 설정 아이콘이 계정 관리 진입점이다.","x":0,"y":11537,"w":1850,"h":2218,"rowTops":[190,1234],"rowBottoms":[1034,2078],"items":[{"name":"40-library","label":"보관함 · 동선","file":"png/40-library.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":844},{"name":"41-library-course","label":"보관함 · 러닝코스","file":"png/41-library-course.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":844},{"name":"42-library-fav","label":"보관함 · 찜한 대회","file":"png/42-library-fav.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":844},{"name":"17-coursedetail-saved","label":"코스 상세 · 보관함에 저장","file":"png/17-coursedetail-saved.png","row":1,"col":1,"x":730,"y":1234,"w":390,"h":844},{"name":"16-account","label":"내 정보 · 계정 관리","file":"png/16-account.png","row":1,"col":2,"x":1380,"y":1234,"w":390,"h":844}]},{"id":"guest","title":"[게스트 · 로그인 유도와 복귀]","fill":"#F3F0FF","note":"게스트가 찜·저장을 누르면 동작별 문구의 로그인 모달이 뜬다. 로그인 후에는 원래 있던 화면으로 돌아오고, 저장·찜은 자동 실행하지 않는다 — 사용자가 다시 누른다.","x":0,"y":14055,"w":2500,"h":1174,"rowTops":[190],"rowBottoms":[1034],"items":[{"name":"72-guest-fav","label":"게스트 · 찜 시도 (로그인 유도)","file":"png/72-guest-fav.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":844},{"name":"73-guest-route","label":"게스트 · 동선 저장 시도 (로그인 유도)","file":"png/73-guest-route.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":844},{"name":"74-guest-course","label":"게스트 · 코스 저장 시도 (로그인 유도)","file":"png/74-guest-course.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":844},{"name":"75-login-return","label":"로그인 후 원래 화면 복귀","file":"png/75-login-return.png","row":0,"col":3,"x":2030,"y":190,"w":390,"h":844}]},{"id":"confirm","title":"[되돌릴 수 없는 동작 · 확인]","fill":"#FFF0F3","note":"삭제·탈퇴처럼 복구가 어려운 동작 앞에 확인 모달을 세운다. [취소]와 [삭제]/[탈퇴] 두 갈래.","x":0,"y":15529,"w":3150,"h":1174,"rowTops":[190],"rowBottoms":[1034],"items":[{"name":"100-confirm-route","label":"확인 · 동선 삭제","file":"png/100-confirm-route.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":844},{"name":"101-confirm-course","label":"확인 · 저장 코스 삭제","file":"png/101-confirm-course.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":844},{"name":"102-confirm-run","label":"확인 · 러닝 기록 삭제","file":"png/102-confirm-run.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":844},{"name":"103-confirm-quit","label":"확인 · 회원 탈퇴","file":"png/103-confirm-quit.png","row":0,"col":3,"x":2030,"y":190,"w":390,"h":844},{"name":"104-confirm-runsave","label":"확인 · 기록 저장 전 취소","file":"png/104-confirm-runsave.png","row":0,"col":4,"x":2680,"y":190,"w":390,"h":844}]},{"id":"states","title":"[사용자 조작으로 도달하는 상태]","fill":"#F1FEFD","note":"버튼·탭·입력으로 도달하는 중간 상태들. 위 플로우의 각 화면에서 한 번의 조작으로 갈라져 나온다.","x":0,"y":17003,"w":2500,"h":6978,"rowTops":[190,1234,2431,3720,5158],"rowBottoms":[1034,2231,3520,4958,6838],"items":[{"name":"80-signup-1-agreed","label":"회원가입 1 · 전체 동의 (다음 활성)","file":"png/80-signup-1-agreed.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":844},{"name":"81-signup-3-filled","label":"회원가입 3 · 인증코드 입력 완료","file":"png/81-signup-3-filled.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":844},{"name":"82-findpw-sent","label":"비밀번호 찾기 · 링크 발송 완료","file":"png/82-findpw-sent.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":844},{"name":"83-newpw-changed","label":"비밀번호 재설정 · 변경 완료","file":"png/83-newpw-changed.png","row":0,"col":3,"x":2030,"y":190,"w":390,"h":844},{"name":"84-calendar-selday","label":"캘린더 · 대회 있는 날짜 선택","file":"png/84-calendar-selday.png","row":1,"col":0,"x":80,"y":1234,"w":390,"h":870},{"name":"85-calendar-nextmonth","label":"캘린더 · 다음 달","file":"png/85-calendar-nextmonth.png","row":1,"col":1,"x":730,"y":1234,"w":390,"h":997},{"name":"86-calendar-filtered","label":"캘린더 · 필터 적용 (칩 노출)","file":"png/86-calendar-filtered.png","row":1,"col":2,"x":1380,"y":1234,"w":390,"h":844},{"name":"87-calendar-noresult","label":"캘린더 · 조건에 맞는 대회 0건","file":"png/87-calendar-noresult.png","row":1,"col":3,"x":2030,"y":1234,"w":390,"h":844},{"name":"88-calendar-search","label":"캘린더 · 검색 결과","file":"png/88-calendar-search.png","row":2,"col":0,"x":80,"y":2431,"w":390,"h":844},{"name":"89-w1-custom","label":"위저드 1 · 직접 날짜 선택","file":"png/89-w1-custom.png","row":2,"col":1,"x":730,"y":2431,"w":390,"h":1073},{"name":"90-w2-none","label":"위저드 2 · 미선택 (다음 비활성)","file":"png/90-w2-none.png","row":2,"col":2,"x":1380,"y":2431,"w":390,"h":844},{"name":"91-w3-picked","label":"위저드 3 · 숙소 선택됨","file":"png/91-w3-picked.png","row":2,"col":3,"x":2030,"y":2431,"w":390,"h":1089},{"name":"92-w3-generating","label":"위저드 3 · 동선 생성 중","file":"png/92-w3-generating.png","row":3,"col":0,"x":80,"y":3720,"w":390,"h":1089},{"name":"93-result-d1","label":"동선 결과 · D-1","file":"png/93-result-d1.png","row":3,"col":1,"x":730,"y":3720,"w":390,"h":1021},{"name":"94-result-dplus1","label":"동선 결과 · D+1 (회복 모드)","file":"png/94-result-dplus1.png","row":3,"col":2,"x":1380,"y":3720,"w":390,"h":1238},{"name":"95-result-edit-rm","label":"동선 결과 · 편집 · 블록 삭제 후","file":"png/95-result-edit-rm.png","row":3,"col":3,"x":2030,"y":3720,"w":390,"h":988},{"name":"96-courses-region-sel","label":"러닝 코스 · 지역 선택","file":"png/96-courses-region-sel.png","row":4,"col":0,"x":80,"y":5158,"w":390,"h":889},{"name":"97-courses-longdist","label":"러닝 코스 · 목표 20km (코스 짧음 안내)","file":"png/97-courses-longdist.png","row":4,"col":1,"x":730,"y":5158,"w":390,"h":1680},{"name":"98-detail-closed","label":"대회 상세 · 접수 마감","file":"png/98-detail-closed.png","row":4,"col":2,"x":1380,"y":5158,"w":390,"h":1089},{"name":"99-detail-before","label":"대회 상세 · 접수 전","file":"png/99-detail-before.png","row":4,"col":3,"x":2030,"y":5158,"w":390,"h":1089}]},{"id":"errors","title":"[예외 상태 — 로딩 · 빈 · 오류]","fill":"#FFF5F5","note":"목업의 STATE 디버그 레일로만 도달하는 예외 상태. 각 캡션 앞부분이 원래 소속 화면이다.","x":0,"y":24281,"w":2500,"h":3800,"rowTops":[190,1367,2411],"rowBottoms":[1167,2211,3660],"items":[{"name":"60-login-error","label":"로그인 · 오류","file":"png/60-login-error.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":844},{"name":"61-detail-loading","label":"대회 상세 · 인근 축제 로딩","file":"png/61-detail-loading.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":977},{"name":"62-detail-empty","label":"대회 상세 · 인근 축제 없음","file":"png/62-detail-empty.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":965},{"name":"63-w3-loading","label":"위저드 3 · 숙소 로딩","file":"png/63-w3-loading.png","row":0,"col":3,"x":2030,"y":190,"w":390,"h":844},{"name":"64-result-empty","label":"동선 결과 · 빈 상태","file":"png/64-result-empty.png","row":1,"col":0,"x":80,"y":1367,"w":390,"h":844},{"name":"65-result-poi-swap","label":"동선 결과 · POI 교체 시트","file":"png/65-result-poi-swap.png","row":1,"col":1,"x":730,"y":1367,"w":390,"h":844},{"name":"70-library-empty","label":"보관함 · 빈 상태","file":"png/70-library-empty.png","row":1,"col":2,"x":1380,"y":1367,"w":390,"h":844},{"name":"71-coursedetail-ran","label":"코스 상세 · 뛴 기록","file":"png/71-coursedetail-ran.png","row":1,"col":3,"x":2030,"y":1367,"w":390,"h":844},{"name":"66-courses-locating","label":"러닝 코스 · 위치 확인 중","file":"png/66-courses-locating.png","row":2,"col":0,"x":80,"y":2411,"w":390,"h":844},{"name":"67-courses-nostart","label":"러닝 코스 · 출발지 없음","file":"png/67-courses-nostart.png","row":2,"col":1,"x":730,"y":2411,"w":390,"h":844},{"name":"68-courses-nocourse","label":"러닝 코스 · 코스 0","file":"png/68-courses-nocourse.png","row":2,"col":2,"x":1380,"y":2411,"w":390,"h":1249},{"name":"69-courses-none","label":"러닝 코스 · 결과 0건","file":"png/69-courses-none.png","row":2,"col":3,"x":2030,"y":2411,"w":390,"h":844}]},{"id":"partial","title":"[영역별 상태 · 부분 실패]","fill":"#F5F5FF","note":"한 화면에서 여러 API를 부를 때 한쪽 실패가 화면 전체를 막지 않는다. 홈은 마감임박·축제가, 캘린더는 목록·날짜 수가 따로 실패한다. 오프라인에서는 마지막으로 받아온 데이터를 보여주고 수정 동작을 잠근다.","x":0,"y":28381,"w":2500,"h":6386,"rowTops":[190,1567,2873,4358,5402],"rowBottoms":[1367,2673,4158,5202,6246],"items":[{"name":"110-home-loading","label":"홈 · 마감 임박 로딩","file":"png/110-home-loading.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":1067},{"name":"111-home-empty","label":"홈 · 접수 중 대회 없음","file":"png/111-home-empty.png","row":0,"col":1,"x":730,"y":190,"w":390,"h":1070},{"name":"112-home-error","label":"홈 · 마감 임박 조회 실패","file":"png/112-home-error.png","row":0,"col":2,"x":1380,"y":190,"w":390,"h":1074},{"name":"116-home-offline","label":"홈 · 오프라인","file":"png/116-home-offline.png","row":0,"col":3,"x":2030,"y":190,"w":390,"h":1177},{"name":"113-home-fest-loading","label":"홈 · 축제 로딩","file":"png/113-home-fest-loading.png","row":1,"col":0,"x":80,"y":1567,"w":390,"h":1106},{"name":"114-home-fest-empty","label":"홈 · 추천 축제 없음","file":"png/114-home-fest-empty.png","row":1,"col":1,"x":730,"y":1567,"w":390,"h":1046},{"name":"115-home-fest-error","label":"홈 · 축제만 실패 (부분 실패)","file":"png/115-home-fest-error.png","row":1,"col":2,"x":1380,"y":1567,"w":390,"h":1050},{"name":"120-calendar-loading","label":"캘린더 · 로딩","file":"png/120-calendar-loading.png","row":2,"col":0,"x":80,"y":2873,"w":390,"h":844},{"name":"121-calendar-error","label":"캘린더 · 조회 실패","file":"png/121-calendar-error.png","row":2,"col":1,"x":730,"y":2873,"w":390,"h":844},{"name":"122-calendar-nodots","label":"캘린더 · 날짜 수만 실패 (부분 실패)","file":"png/122-calendar-nodots.png","row":2,"col":2,"x":1380,"y":2873,"w":390,"h":1285},{"name":"123-calendar-searchnone","label":"캘린더 · 검색 결과 없음","file":"png/123-calendar-searchnone.png","row":3,"col":0,"x":80,"y":4358,"w":390,"h":844},{"name":"124-calendar-dayempty","label":"캘린더 · 이 날에는 대회 없음","file":"png/124-calendar-dayempty.png","row":3,"col":1,"x":730,"y":4358,"w":390,"h":844},{"name":"125-calendar-monthempty","label":"캘린더 · 이 달에는 대회 없음","file":"png/125-calendar-monthempty.png","row":3,"col":2,"x":1380,"y":4358,"w":390,"h":844},{"name":"130-library-error-route","label":"보관함 · 동선 조회 실패","file":"png/130-library-error-route.png","row":4,"col":0,"x":80,"y":5402,"w":390,"h":844},{"name":"131-library-error-course","label":"보관함 · 러닝코스 조회 실패","file":"png/131-library-error-course.png","row":4,"col":1,"x":730,"y":5402,"w":390,"h":844},{"name":"132-library-error-fav","label":"보관함 · 찜한 대회 조회 실패","file":"png/132-library-error-fav.png","row":4,"col":2,"x":1380,"y":5402,"w":390,"h":844},{"name":"133-library-offline","label":"보관함 · 오프라인 (수정 비활성)","file":"png/133-library-offline.png","row":4,"col":3,"x":2030,"y":5402,"w":390,"h":844}]},{"id":"ds","title":"[디자인 시스템 v2]","fill":"#F9F9F9","note":"컬러·타이포·카테고리 태그 9종·상태 칩·고도 스트립 기준.","x":0,"y":35067,"w":550,"h":2669,"rowTops":[190],"rowBottoms":[2529],"items":[{"name":"50-sheet","label":"디자인 시스템 v2","file":"png/50-sheet.png","row":0,"col":0,"x":80,"y":190,"w":390,"h":2339}]}],"connectors":[{"from":"01-login","to":"02-signup-1","label":"회원가입","kind":"inner"},{"from":"02-signup-1","to":"03-signup-2","label":"약관 동의","kind":"inner"},{"from":"03-signup-2","to":"04-signup-3","label":"다음","kind":"inner"},{"from":"04-signup-3","to":"05-signup-4","label":"인증 확인","kind":"inner"},{"from":"01-login","to":"06-findpw","label":"비밀번호 찾기","kind":"inner"},{"from":"06-findpw","to":"07-newpw","label":"메일 링크","kind":"inner"},{"from":"07-newpw","to":"01-login","label":"재설정 완료","kind":"inner"},{"from":"05-signup-4","to":"10-home","label":"시작하기","kind":"cross"},{"from":"01-login","to":"10-home","label":"로그인","kind":"cross"},{"from":"01-login","to":"11-home-guest","label":"둘러보기 (게스트)","kind":"cross"},{"from":"11-home-guest","to":"01-login","label":"저장 시 로그인 유도","kind":"cross"},{"from":"10-home","to":"12-calendar","label":"탭 · 캘린더","kind":"inner"},{"from":"12-calendar","to":"13-calendar-cal","label":"월간 보기","kind":"inner"},{"from":"12-calendar","to":"14-calendar-filter","label":"필터","kind":"inner"},{"from":"10-home","to":"15-detail","label":"대회 보기","kind":"inner"},{"from":"12-calendar","to":"15-detail","label":"대회 선택","kind":"inner"},{"from":"10-home","to":"20-w1","label":"이 대회로 동선 만들기","kind":"cross"},{"from":"15-detail","to":"20-w1","label":"동선 만들기","kind":"cross"},{"from":"20-w1","to":"21-w2","label":"일정 입력","kind":"inner"},{"from":"21-w2","to":"22-w3","label":"취향 선택","kind":"inner"},{"from":"22-w3","to":"23-result","label":"숙소 선택","kind":"inner"},{"from":"23-result","to":"24-result-edit","label":"편집","kind":"inner"},{"from":"24-result-edit","to":"25-result-poi","label":"장소 추가","kind":"inner"},{"from":"25-result-poi","to":"23-result","label":"추가 완료","kind":"inner"},{"from":"23-result","to":"40-library","label":"이 동선 저장하기","kind":"cross"},{"from":"10-home","to":"30-courses","label":"탭 · 러닝코스","kind":"cross"},{"from":"30-courses","to":"32-coursedetail","label":"코스 선택","kind":"inner"},{"from":"30-courses","to":"31-courses-region","label":"지역 탭","kind":"inner"},{"from":"30-courses","to":"140-courses-seoul","label":"출발지 · 서울시청","kind":"inner"},{"from":"140-courses-seoul","to":"33-run","label":"여기서 뛰기","kind":"inner"},{"from":"32-coursedetail","to":"33-run","label":"기록 시작","kind":"inner"},{"from":"33-run","to":"34-runsum","label":"종료","kind":"inner"},{"from":"34-runsum","to":"40-library","label":"기록 저장","kind":"cross"},{"from":"40-library","to":"41-library-course","label":"코스 탭","kind":"inner"},{"from":"40-library","to":"42-library-fav","label":"즐겨찾기 탭","kind":"inner"},{"from":"40-library","to":"23-result","label":"동선 카드 선택","kind":"cross"},{"from":"41-library-course","to":"32-coursedetail","label":"저장 코스 선택","kind":"cross"},{"from":"41-library-course","to":"71-coursedetail-ran","label":"러닝 기록 선택","kind":"cross"},{"from":"42-library-fav","to":"15-detail","label":"찜한 대회 선택","kind":"cross"},{"from":"32-coursedetail","to":"17-coursedetail-saved","label":"코스 저장","kind":"cross"},{"from":"17-coursedetail-saved","to":"41-library-course","label":"보관함에서 확인","kind":"inner"},{"from":"32-coursedetail","to":"74-guest-course","label":"코스 저장 (게스트)","kind":"cross"},{"from":"40-library","to":"16-account","label":"설정 아이콘","kind":"inner"},{"from":"16-account","to":"07-newpw","label":"비밀번호 변경","kind":"cross"},{"from":"16-account","to":"01-login","label":"로그아웃","kind":"cross"},{"from":"16-account","to":"103-confirm-quit","label":"회원 탈퇴","kind":"cross"},{"from":"103-confirm-quit","to":"01-login","label":"탈퇴 완료","kind":"cross"},{"from":"12-calendar","to":"72-guest-fav","label":"찜 (게스트)","kind":"cross"},{"from":"23-result","to":"73-guest-route","label":"동선 저장 (게스트)","kind":"cross"},{"from":"72-guest-fav","to":"01-login","label":"로그인하기","kind":"cross"},{"from":"73-guest-route","to":"01-login","label":"로그인하기","kind":"cross"},{"from":"74-guest-course","to":"01-login","label":"로그인하기","kind":"cross"},{"from":"01-login","to":"75-login-return","label":"로그인 성공","kind":"cross"},{"from":"75-login-return","to":"23-result","label":"사용자가 다시 저장","kind":"cross"},{"from":"40-library","to":"100-confirm-route","label":"동선 삭제","kind":"cross"},{"from":"41-library-course","to":"101-confirm-course","label":"코스 삭제","kind":"cross"},{"from":"41-library-course","to":"102-confirm-run","label":"기록 삭제","kind":"cross"},{"from":"34-runsum","to":"104-confirm-runsave","label":"저장 안 하고 나가기","kind":"cross"},{"from":"100-confirm-route","to":"70-library-empty","label":"삭제","kind":"cross"},{"from":"112-home-error","to":"10-home","label":"다시 시도","kind":"cross"},{"from":"121-calendar-error","to":"12-calendar","label":"다시 시도","kind":"cross"},{"from":"130-library-error-route","to":"40-library","label":"다시 시도","kind":"cross"},{"from":"116-home-offline","to":"10-home","label":"새로고침","kind":"cross"}]};

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
