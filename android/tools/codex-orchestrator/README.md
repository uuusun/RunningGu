# RunningGu Codex Orchestrator

ChatGPT Pro의 Codex 사용량과 `codex app-server --listen stdio://`만 사용하는 순수 JVM CLI입니다. OpenAI API 키, Responses API 직접 호출, WebSocket 전송을 사용하지 않습니다.

## 요구 환경

- JDK 17 이상
- 저장소에 포함된 Gradle Wrapper
- `codex` CLI와 ChatGPT 관리형 로그인
- 계정의 `model/list`에 정확히 `gpt-5.6-sol`, `gpt-5.6-luna`가 노출될 것

API 키 인증은 안전하게 중단됩니다. 로그인되지 않은 경우 다음 명령으로 ChatGPT device-code 로그인을 시작할 수 있습니다.

```powershell
codex login --device-auth
```

App Server를 직접 사용하는 호스트는 `account/login/start`에 `{"type":"chatgptDeviceCode"}`를 보낸 뒤 표시된 URL과 코드를 안내하면 됩니다.

## 빌드와 설치

Windows:

```powershell
cd android
$env:JAVA_HOME = "C:\path\to\jdk-17-or-newer"
.\gradlew.bat :tools:codex-orchestrator:compileKotlin
.\gradlew.bat :tools:codex-orchestrator:test
.\gradlew.bat :tools:codex-orchestrator:installDist
```

Unix 계열:

```bash
cd android
./gradlew :tools:codex-orchestrator:compileKotlin
./gradlew :tools:codex-orchestrator:test
./gradlew :tools:codex-orchestrator:installDist
```

배포 스크립트는 다음 경로에 생성됩니다.

- Windows: `android/tools/codex-orchestrator/build/install/codex-orchestrate/bin/codex-orchestrate.bat`
- Unix: `android/tools/codex-orchestrator/build/install/codex-orchestrate/bin/codex-orchestrate`

## 사용법

```powershell
codex-orchestrate doctor
codex-orchestrate models
codex-orchestrate run --goal "로그인 기능을 분석하고 테스트까지 추가해줘" --cwd . --workers 4
codex-orchestrate run --goal "로그인 기능을 수정하고 검증해줘" --cwd . --workers 4 --apply
```

검증된 Sol 계획까지 생성된 실행이 기술적 오류나 사용량 제한으로 중단되었다면, 상태 파일의 계획을 다시 소비하지 않고 재사용할 수 있습니다.
상태에 Sol 검수 피드백이 있으면 거절된 태스크의 첫 재개 시도에도 해당 수정 지시가 전달됩니다.

```powershell
codex-orchestrate run --goal "동일한 원래 목표" --cwd . --workers 4 --plan-state .codex-orchestrator/state/<run-id>/run-state.json --apply
```

`doctor`는 JDK와 Codex 버전, `initialize`/`initialized`, `account/read`, `model/list`, `account/rateLimits/read`를 확인합니다. `models`는 모델 ID와 실제 지원 추론 단계를 그대로 출력합니다.

현재 first-party App Server는 일부 Pro entitlement를 공개 예시의 `pro` 대신 `prolite`라는 `planType`으로 반환합니다. CLI는 두 식별자를 Pro 계열 실행으로 허용하지만 로그와 최종 출력에서는 실제 값을 `prolite` 그대로 보존하며 더 높은 요금제로 바꿔 표기하지 않습니다.

`run`은 다음 순서로 작동합니다.

1. ChatGPT Pro 인증, 모델, 사용량을 검사합니다.
2. Sol 전용 스레드에서 JSON Schema가 강제된 DAG 계획을 만듭니다. `max`가 없으면 실제 지원되는 가장 높은 단계를 경고와 함께 사용합니다.
3. 쓰기 파일이 겹치지 않고 선행 작업이 끝난 Luna 태스크만 최대 `--workers`개 병렬 실행합니다. Luna는 기본적으로 가장 빠른 지원 단계를 쓰며 `HIGH` 태스크만 높은 추론을 사용합니다.
4. Git 저장소의 쓰기 태스크는 최신 `origin/develop`을 fetch한 별도 worktree와 `feature/codex-*` 브랜치에서 실행합니다. 비 Git 작업공간은 격리 복사본을 사용합니다.
5. Sol이 결과와 테스트 증거를 검수하고 실패 태스크만 최대 2회 재시도합니다. 동일 입력 해시는 다시 실행하지 않습니다.
6. 기본 모드는 패치 검수만 수행합니다. `--apply`가 있을 때만 검수 통과 패치를 DAG 순서로 `git apply --check` 후 적용합니다. 충돌 검사 실패 시 사용자 파일은 변경하지 않습니다.
7. 실행 전후 사용량, 계획, 결과, 재시도, 제한 사항을 `.codex-orchestrator/state/<run-id>/run-state.json`과 JSONL 로그에 기록합니다.

worktree와 브랜치는 결과 감사와 수동 적용을 위해 자동 삭제하지 않습니다. 자동 병합과 자동 push도 하지 않습니다.

## 테스트

일반 JUnit은 로컬 Mock App Server만 사용하며 Codex 모델 사용량을 소비하지 않습니다. Mock은 핸드셰이크, Pro 계정, 모델 카탈로그, Sol 계획, Luna 결과, Sol 검수와 사용량 응답을 재현합니다.

실제 계정 통합 테스트는 명시적으로 활성화할 때만 실행됩니다. 이 테스트는 계정/모델/사용량 조회만 하며 모델 turn을 시작하지 않습니다.

```powershell
$env:CODEX_INTEGRATION_TEST = "true"
.\gradlew.bat :tools:codex-orchestrator:test --tests "*RealAccountIntegrationTest"
```

Windows에서 체크아웃 경로에 한글이 있고 JUnit 클래스패스 로딩이 실패하면, 저장소의 ASCII 실제 경로나 `subst` 드라이브에서 같은 Wrapper 명령을 실행하세요.

## 보안과 제한

- `OPENAI_API_KEY`를 읽거나 전송하지 않습니다.
- 인증 유형이 `apiKey`이면 즉시 실패합니다.
- 필수 모델이 없거나 turn 중 모델이 reroute되면 대체 모델을 사용하지 않고 실패합니다.
- non-interactive 실행 중 추가 승인이 필요한 명령/파일 변경은 승인하지 않습니다.
- 외부 네트워크, 조직 정책, 사용량 제한, 로그인 만료와 실제 모델 권한은 Codex/App Server가 결정합니다.
- 현재 상태 파일의 재개 명령은 동일 목표 재실행 명령입니다. 부분 worktree는 보존되지만 프로세스 내부 turn을 이어 붙이는 체크포인트 재개는 App Server 제한상 제공하지 않습니다.
