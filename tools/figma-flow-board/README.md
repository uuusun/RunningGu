# 런닝구 화면 플로우 보드 — Figma 플러그인

`docs/mockup-design/shots/png/` 의 캡처 88장을 Figma 디자인 파일에 섹션·캡션·화살표까지
얹어 한 번에 그린다. **Figma MCP 는 쓰지 않는다** — Starter 플랜은 툴 호출이 월 20회라
88장을 옮기다 중간에 끊긴다. 플러그인은 호출 한도가 없어서 몇 번이든 다시 돌릴 수 있다.

## 한 번만 하는 설치

1. Figma **데스크톱 앱**을 연다 (브라우저판은 로컬 플러그인을 못 읽는다).
2. 아무 디자인 파일이나 연 뒤 메뉴 → `Plugins` → `Development` → `Import plugin from manifest…`
3. 이 폴더의 `manifest.json` 을 고른다.

## 쓰는 법

1. 보드를 그릴 Figma 디자인 파일을 연다.
2. `Plugins` → `Development` → **런닝구 화면 플로우 보드** 실행.
3. `PNG 고르기` → `docs/mockup-design/shots/png/` 로 가서 `Ctrl+A` 로 88장 전체 선택.
4. `보드 그리기`.

**화면 플로우 v2** 라는 새 페이지가 생기고 거기에 보드가 그려진다. 기존 페이지는 건드리지
않으니 여러 번 돌려서 마음에 드는 걸 남기고 나머지 페이지를 지우면 된다.

## 무엇이 그려지나

| | |
|---|---|
| 섹션 | 11개 — 색 배경 프레임 + 제목 + 설명 |
| 화면 | 88장 — 캡처 이미지 + 아래 캡션 |
| 화살표 | 61개 — 섹션 내부 파랑 `#3DADFF`, 섹션 간 보라 `#874FFF`, 라벨 포함 |
| 보드 크기 | 3800 × 36473 |

## 좌표·화면을 바꾸려면

이 폴더의 `code.js` 는 **자동 생성 파일이라 직접 고치면 다음 빌드에 날아간다.**
고칠 곳은 셋 중 하나다.

| 바꾸고 싶은 것 | 고칠 파일 |
|---|---|
| 어느 화면을 어느 섹션·행에 둘지, 화살표 목록 | `docs/mockup-design/shots/build-layout.mjs` 의 `SECTIONS` · `CONNECTORS` |
| 화살표가 지나가는 길 | `router.js` |
| 섹션·캡션 그리는 방식 | `code.template.js` |

고친 뒤 반드시 다시 빌드한다.

```bash
cd docs/mockup-design/shots
node build-layout.mjs     # layout.json · README.md · 플러그인 code.js 를 다시 만든다
node check-routing.mjs    # 화살표가 다른 화면을 밟지 않는지 검산
```

화면 자체(목업)를 고쳤다면 캡처부터 다시 뜬다.

```bash
cd docs/mockup-design/shots
node capture-screens.mjs  # png/ 와 manifest.json 갱신
node build-layout.mjs
node check-routing.mjs
```

## 파일

| 파일 | 역할 |
|---|---|
| `manifest.json` | Figma 플러그인 선언 |
| `ui.html` | PNG 고르는 창. 4096px 넘는 캡처는 비율 지켜 줄여서 보낸다 |
| `code.template.js` | 보드를 그리는 로직 (사람이 고치는 원본) |
| `router.js` | 화살표 경로 계산. 플러그인과 검산 스크립트가 공유한다 |
| `code.js` | 좌표 + `router.js` + `code.template.js` 를 합친 자동 생성 결과 |
