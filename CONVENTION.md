# Git 컨벤션 (런닝구)

> 팀 협업을 위한 Git 규칙 문서.
> 브랜치 전략: **Git Flow** (`main` + `develop` + 작업 브랜치)

---

## 1. 기본 원칙

1. **`main`·`develop`에 직접 push 금지.** 모든 변경은 작업 브랜치 → PR → 리뷰 → 머지.
2. `main`은 **배포된 상태**, `develop`은 **다음 배포에 들어갈 통합 상태**를 유지한다.
3. 작업은 항상 최신 `develop`에서 브랜치를 따서 시작한다.
4. 작은 단위로 자주 커밋하고, 자주 push 한다.

---

## 2. 브랜치 전략 (Git Flow)

| 브랜치 | 역할 | 어디서 따나 | 어디로 PR |
|--------|------|---|---|
| `main` | 배포 기준. 릴리스 시점 스냅샷 | — | `develop`에서만 |
| `develop` | 통합 브랜치. 기본 브랜치 | — | 작업 브랜치에서 |
| `<종류>/<설명>` | 기능·수정 등 실제 작업 | `develop` | `develop` |
| `hotfix/<설명>` | 배포본 긴급 수정 | `main` | `main` + `develop` 양쪽 |

> 평소 작업은 **`develop` → 작업 브랜치 → `develop`** 한 사이클만 돌면 된다.
> `main`은 릴리스할 때만 건드린다.

### 작업 흐름

```bash
# 1. develop 최신화
git checkout develop && git pull

# 2. 작업 브랜치 생성
git checkout -b feature/calendar-filter-modal

# 3. 작업 + 커밋
git add .
git commit -m "feat(calendar): 필터 모달 추가"

# 4. 원격 push
git push -u origin feature/calendar-filter-modal

# 5. GitHub에서 develop 대상 PR 생성 → 팀원 리뷰
# 6. 승인 후 머지 → 브랜치 삭제
```

### 브랜치 이름 규칙

`<종류>/<간단한-설명>` 형태, 소문자 + 하이픈(`-`).

- `feature/calendar-filter-modal`
- `fix/map-pin-position`
- `docs/update-readme`
- `data/marathon-crawl`
- `hotfix/login-crash`

> ⚠️ **브랜치 접두사와 커밋 종류는 한 글자 다르다.**
> 기능 브랜치는 `feature/`(Git Flow 표준), 커밋 종류는 `feat`(Conventional Commits).
> 나머지(`fix`·`docs`·`data`·`chore`·`refactor`·`style`·`test`)는 양쪽이 같다.

---

## 3. 커밋 메시지 규칙

Conventional Commits 형식을 따른다.

```
<종류>(<범위>): <설명>
```

- **종류**: 필수. 아래 표 참고.
- **범위**: 선택. 어떤 영역인지 (`auth`, `home`, `calendar`, `map`, `domain` 등).
- **설명**: 필수. "무엇을 했는지" 한국어로 간결하게. 마침표 없이.

### 커밋 종류

| 종류 | 용도 |
|------|------|
| `feat` | 새로운 기능 추가 (브랜치는 `feature/`) |
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
data: 마라톤 대회 크롤링 152건 갱신
docs: SPEC.md 회의 반영 내용 업데이트
chore: .env를 gitignore에 등록
```

본문이 필요하면 제목 다음에 빈 줄을 두고 "왜 바꿨는지"를 적는다.

---

## 4. Pull Request (PR) 규칙

1. **대상 브랜치**: `develop` (hotfix만 `main`).
2. **제목**: 커밋 메시지와 같은 형식. 예) `feat(home): 홈 검색 기능 추가`
3. **본문에 포함**: 무엇을 / 왜 바꿨는지, 확인 방법, (있으면) 스크린샷.
4. **작게 나누기**: PR 하나에 기능 하나. 리뷰하기 쉽게.
5. **최소 1명 리뷰 승인** 후 머지.
6. 머지 방식은 **Squash and merge**로 통일 → 히스토리 깔끔.
7. 머지 후 작업 브랜치는 **삭제**한다.

### PR 본문 템플릿

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

## 5. 릴리스 (`develop` → `main`)

공모전 제출 등 배포 시점에만 수행한다.

```bash
git checkout develop && git pull
# GitHub에서 develop → main PR 생성 → 리뷰 → 머지
git checkout main && git pull
git tag -a v1.0.0 -m "공모전 제출본"
git push origin v1.0.0
```

배포본에서 급한 버그가 나오면 `main`에서 `hotfix/`를 따고, 고친 뒤 **`main`과 `develop` 양쪽에 머지**한다. (develop에 반영을 빠뜨리면 다음 릴리스에서 버그가 되살아난다.)

---

## 6. 브랜치 보호 설정 (저장소 관리자)

규칙을 GitHub 설정으로 강제한다.

`Settings → Branches → Add branch ruleset` → 대상 `main`, `develop` **각각**:

- ✅ Require a pull request before merging (PR 없이 push 금지)
- ✅ Require approvals (최소 1명)
- ✅ (권장) Require branches to be up to date before merging

기본 브랜치는 `develop`으로 둔다 (`Settings → General → Default branch`) — PR 대상이 자동으로 `develop`이 되어 실수로 `main`에 올리는 걸 막는다.

---

## 7. 자주 쓰는 명령어

```bash
# 최신 develop 받아오기
git checkout develop && git pull

# 새 작업 브랜치 만들기
git checkout -b feature/기능이름

# 변경사항 커밋
git add .
git commit -m "feat(범위): 설명"

# 원격에 올리기 (처음 push할 때)
git push -u origin feature/기능이름

# 작업 중 develop 최신 내용 반영하고 싶을 때
git checkout develop && git pull
git checkout feature/기능이름
git merge develop        # 또는 git rebase develop

# 머지된 로컬 브랜치 정리
git branch -d feature/기능이름
git remote prune origin
```

---

## 8. 기타 규칙

- **`local.properties`, `.env`, API 키, `/build`, `.gradle/`, `.idea/`, `*.jks`, `.DS_Store`** 는 반드시 `.gitignore`에 등록하고 커밋하지 않는다.
  - 앱 키는 `local.properties`, 백엔드·스크립트 키는 각자 `.env` (SPEC §9.4)
  - 실수로 커밋했다면 파일만 지우지 말고 **키를 재발급**한다. 히스토리에 남는다.
- 커밋 전 동작 확인. `develop`이 깨지지 않게 한다.
- 충돌(conflict)이 나면 임의로 덮어쓰지 말고, 관련된 팀원과 확인 후 해결한다.
