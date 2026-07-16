# Git 컨벤션 (런닝구)

> 팀 협업을 위한 Git 규칙 문서. 새 저장소는 이 규칙을 기준으로 운영한다.
> 브랜치 전략: **GitHub Flow**

---

## 1. 기본 원칙

1. **`main`에 직접 push 금지.** 모든 변경은 브랜치 → PR → 리뷰 → 머지.
2. `main`은 **항상 배포 가능한(작동하는) 상태**를 유지한다.
3. 작업은 항상 최신 `main`에서 브랜치를 따서 시작한다.
4. 작은 단위로 자주 커밋하고, 자주 push 한다.

---

## 2. 브랜치 전략 (GitHub Flow)

구조는 단순하게 두 종류만 사용한다.

| 브랜치 | 역할 | 규칙 |
|--------|------|------|
| `main` | 완성/배포 기준 브랜치 | 직접 push 금지, PR로만 머지 |
| `<종류>/<내용>` | 기능·수정 작업 브랜치 | `main`에서 따고, 끝나면 `main`으로 PR |

### 작업 흐름

```
1. main 최신화        git checkout main && git pull
2. 브랜치 생성        git checkout -b feat/login-page
3. 작업 + 커밋        git add . && git commit -m "feat(auth): 로그인 화면 추가"
4. 원격 push          git push -u origin feat/login-page
5. GitHub에서 PR 생성 → 팀원 리뷰
6. 승인 후 머지 → 브랜치 삭제
```

### 브랜치 이름 규칙

`<종류>/<간단한-설명>` 형태, 소문자 + 하이픈(`-`) 사용.

- `feat/calendar-filter-modal`
- `fix/map-pin-position`
- `data/marathon-crawl`
- `docs/update-readme`

> 종류는 아래 커밋 종류(3번)와 동일하게 사용한다.

---

## 3. 커밋 메시지 규칙

Conventional Commits 형식을 따른다.

```
<종류>(<범위>): <설명>
```

- **종류**: 필수. 아래 표 참고.
- **범위**: 선택. 어떤 영역인지 (`auth`, `home`, `calendar`, `map`, `community` 등).
- **설명**: 필수. "무엇을 했는지" 한국어로 간결하게. 마침표 없이.

### 커밋 종류

| 종류 | 용도 |
|------|------|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 (README, SPEC 등) |
| `style` | 코드 포맷·세미콜론 등 (동작 변화 없음) |
| `refactor` | 기능 변화 없는 코드 구조 개선 |
| `data` | 데이터 추가·수정 (크롤링, CSV/JSON 등) |
| `chore` | 설정, 패키지, 빌드, 잡일 |
| `test` | 테스트 코드 |

### 예시

```
feat(auth): 카카오 로그인 버튼 추가
fix(calendar): 필터 모달 닫힘 오류 수정
data: 마라톤 대회 크롤링 153건 갱신
docs: SPEC.md 회의 반영 내용 업데이트
chore: .env를 gitignore에 등록
```

---

## 4. Pull Request (PR) 규칙

1. **제목**: 커밋 메시지와 같은 형식. 예) `feat(home): 홈 검색 기능 추가`
2. **본문에 포함**: 무엇을 / 왜 바꿨는지, 확인 방법, (있으면) 스크린샷.
3. **작게 나누기**: PR 하나에 기능 하나. 리뷰하기 쉽게.
4. **최소 1명 리뷰 승인** 후 머지.
5. 머지 방식은 팀에서 하나로 통일 (권장: **Squash and merge** → 히스토리 깔끔).
6. 머지 후 작업 브랜치는 **삭제**한다.

### PR 본문 템플릿 (예시)

```markdown
## 작업 내용
- 로그인 화면 UI 구현
- 카카오 로그인 버튼 연결

## 확인 방법
- /login 접속 → 카카오 버튼 클릭 시 동작 확인

## 참고
- 이메일 인증 흐름은 다음 PR에서 진행
```

---

## 5. `main` 브랜치 보호 설정 (저장소 관리자)

규칙을 GitHub 설정으로 강제한다. **저장소 생성 직후 반드시 켤 것.**

`Settings → Branches → Add branch ruleset` (또는 Branch protection rules) → 대상 `main`:

- ✅ Require a pull request before merging (PR 없이 push 금지)
- ✅ Require approvals (최소 1명)
- ✅ (권장) Require branches to be up to date before merging

---

## 6. 자주 쓰는 명령어

```bash
# 최신 main 받아오기
git checkout main && git pull

# 새 작업 브랜치 만들기
git checkout -b feat/기능이름

# 변경사항 커밋
git add .
git commit -m "feat(범위): 설명"

# 원격에 올리기 (처음 push할 때)
git push -u origin feat/기능이름

# 작업 중 main 최신 내용 반영하고 싶을 때
git checkout main && git pull
git checkout feat/기능이름
git merge main        # 또는 git rebase main

# 머지된 로컬 브랜치 정리
git branch -d feat/기능이름
```

---

## 7. 기타 규칙

- **`local.properties`, API 키, `/build`, `.gradle/`, `.idea/`, `.DS_Store`** 는 반드시 `.gitignore`에 등록하고 커밋하지 않는다. (안드로이드 프로젝트 기준)
- 커밋 전 동작 확인. `main`이 깨지지 않게 한다.
- 충돌(conflict)이 나면 임의로 덮어쓰지 말고, 관련된 팀원과 확인 후 해결한다.
