# 런닝구 디자인 시스템 번들

`reference-web/`의 시각 아이덴티티(STYLE A "활기찬 스포티")를 **Claude Design(claude.ai/design) 디자인 시스템 프로젝트**로 올리기 위해 정리한 카드 번들이다.

- **원천**: `reference-web/src/styles/tokens.css`(토큰) · `base.css`(컴포넌트 스타일) · `components/`·`screens/`(마크업 패턴)
- **기준 명세**: SPEC v3 — 목업과 달라진 부분은 SPEC을 따랐다 (탭 4개 구성, 찜 하트, "런닝구" 표기, home 아이콘 신규)
- 각 HTML은 **첫 줄의 `<!-- @dsCard … -->` 마커**로 Claude Design 패널에 카드로 등록된다. 파일은 외부 의존성 없는 단독 렌더 파일이다(브라우저로 바로 열어 확인 가능).

> ## ⚠️ 상태 (2026-08-17)
>
> 이 번들은 **2026-07-23**에 `reference-web/tokens.css` 기준으로 만들었다. 그 시점 토큰과는 지금도 정확히 일치한다.
>
> 이후 **목업 v2(08-04 추가 · 08-17 개정)** 에서 토큰이 늘고 일부 값이 바뀌었다.
>
> | 목업 v2 에서 추가·변경 | 값 |
> |---|---|
> | 다크 히어로 3종 🆕 | `--deep #0C1024` · `--deep-2 #131A38` · `--deep-3 #1B2450` |
> | 라임 진한 톤 🆕 | `--lime-deep #B3DC28` |
> | 회색 5단계 🆕 | `--ink2 #54565E` · `--ink3 #71747C` · `--ink4 #86878E` · `--ink5 #9A9CA2` |
> | 보조 톤 🆕 | `--blue-soft2` · `--fill2` · `--line2` · `--line3` |
> | 페이지 배경 **변경** | `#e7e5df` → `#EDECE8` |
>
> 앱 `android/.../ui/theme/Color.kt` 는 **목업 v2 를 따른다.**
>
> **색 값이 다르면 목업 v2 와 앱 테마가 최신이다.** 이 번들은 **컴포넌트 구조·패턴 참고용**으로 쓴다.
> 토큰 값을 확인할 때는 `docs/mockup-design/런닝구-목업-v2.html` 상단의 CSS 변수를 본다.

## 카드 구성 (17장)

| 그룹 | 파일 | 내용 |
|---|---|---|
| 파운데이션 | `foundations/colors.html` | 브랜드·잉크·표면·카테고리 9종·접수 상태 |
| 파운데이션 | `foundations/typography.html` | Pretendard/Archivo 타입 스케일 13단 |
| 파운데이션 | `foundations/shape.html` | 라운드·그림자·간격(pad-x 22 · CTA 56) |
| 파운데이션 | `foundations/icons.html` | 24px 라인 아이콘 23종 + home(v3 신규) |
| 컨트롤 · 입력 | `components/buttons.html` | CTA 바·상태(비활성/로딩)·보조 버튼 |
| 컨트롤 · 입력 | `components/chips.html` | 필터 칩·테마 칩·세그먼트·뷰 토글 |
| 컨트롤 · 입력 | `components/inputs.html` | 검색 인풋·출발지 검색·거리 슬라이더 |
| 배지 · 피드백 | `components/tags-badges.html` | 종목 태그·접수 상태·카테고리 태그·소스 배지 |
| 배지 · 피드백 | `components/feedback.html` | 회복 배지·인포박스·스낵바·빈 상태 |
| 카드 | `components/race-card.html` | 대회 카드 featured/기본/마감 + 찜 하트 |
| 카드 | `components/list-cards.html` | 코스·걷기 스팟·축제·저장 동선 카드 |
| 내비게이션 | `components/navigation.html` | 앱바 3형 · 하단 탭 4개(SPEC v3) |
| 캘린더 · 동선 | `components/calendar.html` | 미니 캘린더 · 월 캘린더 셀 상태 |
| 캘린더 · 동선 | `components/timeline.html` | 일자 탭·타임라인·편집 행 |
| 캘린더 · 동선 | `components/bottom-sheet.html` | 후보 시트(교체·추가) |
| 캘린더 · 동선 | `components/map.html` | 번호 핀·폴리라인·범례·오버레이 |
| 캘린더 · 동선 | `components/hero.html` | 잉크 히어로 + D-day · 정보 행 |

## 동기화 방법

Claude Code에서 `/design-sync`를 실행하거나, DesignSync 도구로 이 디렉터리를 `런닝구 디자인 시스템` 프로젝트에 업로드한다.
컴포넌트를 수정하면 **해당 파일만** 다시 올린다(전체 교체 금지).

## 수정 규칙

- 색·크기 값은 임의로 바꾸지 않는다 — `tokens.css`가 단일 출처, 여기 값은 그 사본이다.
- reference-web과 달라지는 결정(예: 탭 구성)은 SPEC을 먼저 갱신하고 그 근거를 카드 주석에 남긴다.
