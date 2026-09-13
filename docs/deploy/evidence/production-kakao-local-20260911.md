# 운영 카카오 로컬 호출 실패 — 2026-09-11

## 무엇을 확인했나

운영 서버(`https://api.runninggu.store/api/`)에서 **카카오 로컬 REST 호출만 전면 실패한다.**
KTO·두루누비·GraphHopper 는 정상이다. 같은 시각 스테이징(`https://staging-api.runninggu.store/api/`)은
같은 요청에 정상 응답한다.

**원인은 운영 카카오 앱에서 카카오맵/로컬 서비스가 꺼져 있는 것이었다.** 키는 정상적으로
주입돼 있었고 스테이징과 다른 운영 전용 키였다(아래 「원인 — 확정」).

**2026-09-11 해소됐다.** 결정-66에 따라 운영 카카오 앱을 `1442028` 로 바꾸고 백엔드를
재기동해 `degradedSources` 가 비었다(아래 「해소 기록」).

최종 서명 APK(`42bfec46`) 운영 회귀 중에 발견했다
([릴리스 APK 검증 3회차](../../release/release-apk-verification.md)).

## 대조

| 요청 | 운영 | 스테이징 |
|---|---|---|
| `GET /api/geocode?query=서울시청` | **502** `EXTERNAL_API_ERROR` · `traceId 64ac39a99664466c9aeb68dfd44ef83d` | `200` `서울특별시청 / 37.56682420267543 / 126.978652258823` |
| `GET /api/geocode?query=해운대해수욕장` | **502** — 2초 간격 3회 모두 | — |
| `GET /api/courses/near?lat=37.5665&lng=126.9780&targetKm=5` | `200` · `ROUTE` 1건 · `PLACE` **0건** · `degradedSources:["KAKAO"]` · `attributions:["© OpenStreetMap contributors"]` | `200` · `ROUTE` 1건 · `PLACE` **11건** = **12건**(계약 상한) |
| `GET /api/courses/near?lat=35.1587&lng=129.1604&targetKm=5` | `200` · `degradedSources:["KAKAO"]` · `attributions:["두루누비 걷기길(한국관광공사)"]` | — |
| `GET /api/pois?category=LODGING&lat=37.2073&lng=128.8258` | `200` — 단 응답 항목이 전부 `"provider":"KTO"` | `200` |

`/api/geocode` 는 카카오 키워드 검색만 쓰므로 502 로 떨어진다. 걷기 스팟은 카카오 키워드 6종
(공원·산책로·둘레길·하천·한강공원·생태공원)을 쓰므로 전부 실패해 `PLACE` 가 0건이 된다
(API 명세 §4-3). `/api/pois` 는 KTO 가 함께 원천이라 카카오가 죽어도 결과가 나온다 —
**앱 화면의 "숙소 검색 · 카카오 로컬" 표기가 결과를 냈다고 카카오가 살아 있는 것이 아니다.**

## 앱은 계약대로 동작한다

앱 버그가 아니다. 서버가 API 명세 §0-5 대로 `200` + `degradedSources` 부분 성공을 반환하고,
앱은 그것을 받아 `일부 정보를 불러오지 못했어요. 보이는 것만 표시합니다.` 를 띄우고 나머지
항목을 그린다. SPEC §5(508행)의 "GraphHopper 실패는 큐레이션·카카오 걷기 스팟과 격리한다"
설계가 반대 방향(카카오 실패)에서도 그대로 작동했다.

## 왜 중요한가

SPEC §5(347행)는 **서울 반경 8km 내 두루누비 코스가 0건**이라고 적고 있다. 그래서 서울
출발지에서는 OSM 생성 경로 1건만 남고, AGENTS 6장이 "걷기 스팟은 폴백이 아니라 수도권의
기본 경험"이라고 못박은 그 경험이 운영에서 통째로 빠진다. 출발지 검색(`/api/geocode`)도 함께
죽어 있어 프리셋 5개 외의 출발지를 고를 수 없다.

## 원인 — 확정

**운영 카카오 앱에서 카카오맵/로컬 서비스가 꺼져 있다.** 키 문제가 아니다.

운영 인스턴스(`i-0c9f040b65d41d6c1`)에서 백엔드 프로세스에 실제로 주입된 키로 카카오를
직접 호출한 결과다. 키 값은 출력하지 않았다.

```text
GET https://dapi.kakao.com/v2/local/search/keyword.json
  운영     403  {"errorType":"NotAuthorizedError",
                "message":"App(런닝구 앱) disabled OPEN_MAP_AND_LOCAL service."}
  스테이징  200  정상 (서울특별시청 / 367건)

GET https://dapi.kakao.com/v2/local/search/category.json
  운영     403
```

`401` 이 아니라 `403` 이고 카카오가 앱을 `런닝구 앱` 으로 식별했다. **인증은 통과했고 그 앱에
로컬 API 사용 권한이 없는 것이다.**

## 확인한 사실

| 확인 항목 | 결과 |
|---|---|
| 운영 `KAKAO_REST_KEY` 설정 여부 | **설정돼 있다.** `/etc/runninggu/application.env` 에 값이 있고 실행 중인 `runninggu-backend.service` 프로세스 환경에 32자로 주입돼 있다 |
| 스테이징과 같은 키인가 | **다르다.** 두 인스턴스의 주입값 SHA-256 을 대조했고 서로 다르다. 운영 실행서 2장의 "Kakao REST key·app ID는 운영 전용으로 생성한다" 와 일치한다 |
| 카카오 콘솔 IP 허용·쿼터 | **둘 다 아니다.** IP 제한이면 인증 단계에서 갈리고 쿼터 초과면 `429` 다. 실제 응답은 서비스 비활성 `403` 이다 |
| `traceId 64ac39a99664466c9aeb68dfd44ef83d` | 백엔드 journal 에서 찾지 못했다. 로그 개인정보 보호(#332 · #337)로 예외 원문이 제거된 뒤라 traceId 로 거슬러 올라가는 경로가 없다 |
| Parameter Store | `/runninggu/production/kakao-rest-key`·`kakao-app-id` 가 있다. `GetParameter` 는 콘솔 root 자격증명으로도 `ParameterNotFound` 라 값을 읽지 못했지만(조직 정책으로 보인다), EC2 role 은 읽을 수 있어 인스턴스 안에서 대조했다 |
| env 파일 · Parameter Store · 주입값 | **셋이 모두 같은 값이다.** 세 곳의 SHA-256 이 일치한다. `application.env` 수정 시각(09-10 10:22)이 Parameter Store 수정 시각(09-10 19:18)보다 이른 것은 값 차이로 이어지지 않았다 |

## 카카오 앱이 스테이징과 다르다 — 로그인도 막힌다

키 대조 중에 `KAKAO_APP_ID` 도 두 환경이 다른 것을 확인했다. 스테이징 값의 SHA-256 은
문서에 기록된 앱 `1442028` 의 해시와 같고, 운영은 다른 7자리 값이다.

서버는 `KakaoUserInfoClient`(42행)에서 `tokenInfo.appId() != expectedAppId` 면 로그인을 거부한다.
APK 의 카카오 **네이티브** 키와 출시 키 해시 `oRJtsESChTHqkP9fnp4lRq+wNxM=` 는 앱 `1442028` 에
등록돼 있으므로, 운영이 다른 앱을 계속 쓰면 **걷기 스팟뿐 아니라 카카오 로그인도 막힌다.**
3회차 검증이 게스트로만 밟아서 드러나지 않았을 뿐이다.

## 고치는 법 — 결정-66

**운영 카카오 앱을 staging 과 같은 앱(`1442028`)으로 통일한다**(SPEC 결정-66 · 2026-09-11
운영책임자 결정). 그 앱은 카카오맵/로컬이 이미 켜져 있고(스테이징 `200`), 출시 키 해시와 앱
네이티브 키도 거기 등록돼 있다. 그래서 **APK 재빌드가 필요 없고** 검증을 마친
`42bfec46` · SHA-256 `61bd4481…afd826` 을 그대로 쓴다.

운영 전용 앱을 유지하는 대안은 카카오맵 활성화에 더해 출시 키 해시 재등록과 네이티브 키
교체·APK 재빌드까지 필요해서 채택하지 않았다.

바꿀 것은 운영의 두 값이다.

| 대상 | 바꿀 값 |
|---|---|
| Parameter Store `/runninggu/production/kakao-rest-key` | staging 과 같은 값 |
| Parameter Store `/runninggu/production/kakao-app-id` | `1442028` |
| `/etc/runninggu/application.env` 의 같은 두 항목 | 위와 같게 다시 만든다 |
| `runninggu-backend.service` | 재기동해야 새 값이 주입된다 |

바꾼 뒤 아래로 확인한다 — 키를 출력하지 않는다.

```bash
aws ssm send-command --region ap-northeast-2 --instance-ids i-0c9f040b65d41d6c1 \
  --document-name AWS-RunShellScript \
  --parameters 'commands=["PID=$(systemctl show -p MainPID --value runninggu-backend.service); K=$(tr \"\0\" \"\n\" < /proc/$PID/environ | grep \"^KAKAO_REST_KEY=\" | cut -d= -f2-); curl -s -o /dev/null -w \"%{http_code}\n\" -H \"Authorization: KakaoAK $K\" \"https://dapi.kakao.com/v2/local/search/keyword.json?query=%EC%84%9C%EC%9A%B8%EC%8B%9C%EC%B2%AD&size=1\""]'
```

`200` 이 나오면 `GET /api/geocode` 와 러닝코스 걷기 스팟이 함께 살아난다. 앱 검증은
[3회차 기록](../../release/release-apk-verification.md)의 7번 항목을 다시 밟는다. **카카오
로그인(2번 항목)은 게스트로 밟을 수 없어 계정을 가진 사람이 따로 확인해야 한다** — 앱 ID 가
바뀌는 변경이라 이번에는 통과 여부를 반드시 봐야 한다.

## 해소 기록 — 2026-09-11

운영책임자가 Parameter Store 의 두 값을 직접 바꾸고(`kakao-rest-key` · `kakao-app-id`),
이어서 env 반영과 재기동을 했다. 키 값은 AWS 콘솔에서만 입력했고 작업 중 어디에도 출력하지 않았다.

```text
① Parameter Store  kakao-app-id  → 1442028 (SHA-256 대조로 확인)
                   kakao-rest-key → 앱 1442028 의 REST 키
② 새 키로 카카오 직접 호출        keyword.json 200 · category.json 200
③ /etc/runninggu/application.env  두 줄 교체 (백업 application.env.bak-20260911-kakao,
                                   줄 수 45 동일, 키 이름 구성 동일)
④ systemctl restart runninggu-backend.service   → active, 08:28:26 UTC
                                   주입값 SHA-256 이 Parameter Store 와 일치
```

**공개 API 확인**

```text
GET /api/geocode?query=서울시청
  200  서울특별시청 / 37.56682420267543 / 126.978652258823

GET /api/courses/near?lat=37.5665&lng=126.9780&targetKm=5
  ROUTE 1 + PLACE 11 = 12건 (계약 상한)
  degradedSources []   attributions ["© OpenStreetMap contributors","카카오 로컬"]

GET /api/courses/near?lat=35.1587&lng=129.1604&targetKm=5
  ROUTE 2 + PLACE 9 = 11건
  degradedSources []   attributions ["두루누비 걷기길(한국관광공사)","카카오 로컬"]
```

**앱 확인** — 같은 서명 APK(`42bfec46`)로 `emulator-5580` 에서 러닝코스 → 서울시청 프리셋 →
목표 5km 를 다시 밟았다. `일부 정보를 불러오지 못했어요` 안내가 사라졌고 경로 1건에 걷기 스팟이
거리순으로 섞여 나온다 — 서울광장 92m · 덕수궁 정관헌 207m · 덕수궁돌담길 266m · 청계광장 287m ·
광화문원표공원 288m · 청계천 299m · 다동공원 308m · 덕수공원 422m …

**카카오 로그인도 확인했다.** 앱 ID 가 `1442028` 로 바뀌어 서버 `app_id` 검증 경로가 달라졌으므로
같은 APK 로 운영책임자가 직접 인증했고, 앱 복귀 후 마이에 닉네임과 `카카오 가입` 배지·보관함
3개 탭이 표시됐다. 서버 응답이므로 우리 서버 로그인까지 통과다. **APK 재빌드 없이 맞아떨어졌다** —
APK 의 카카오 네이티브 키가 같은 앱 것이기 때문이다. R8 릴리스 크래시(#259)도 재현되지 않았다.

## 남은 확인

- 운영·스테이징이 같은 카카오 앱을 쓰게 되므로 **호출 쿼터를 합산 관리**한다. KTO 와 같은
  방식이다(결정-63 · 결정-66). staging 호출이 운영 조회를 막는 상황을 감시한다.
- 운영계정 승인 뒤 운영 전용 앱으로 분리할지 다시 판단한다. 분리할 때는 카카오맵 활성화 ·
  출시 키 해시 등록 · 네이티브 키 교체 · APK 재빌드가 한 묶음이다.

## 출시 차단 관계

걷기 스팟 누락 자체를 출시 차단으로 정한 결정은 없다. 다만 출시 지침 §8 의 기존 BLOCKER 두 줄에
직접 걸린다.

- **운영 배포 미완** — 완료 조건이 "운영 배포 후 `API_BASE_URL` 을 운영 주소로 넣은 APK 검증"이고,
  이 회차가 그 검증이며 실패가 나왔다
- **백엔드 — 배포·E2E 만 남음** — 근거 칸이 "POI·지오코딩이 구현됐다"고 적고 있는데 운영 지오코딩이 502 다

판정과 후속은 백엔드 담당(유선경)이 정한다.
