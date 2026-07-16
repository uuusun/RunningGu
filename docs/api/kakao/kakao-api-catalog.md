# Kakao API 카탈로그

## 기준

- 기준 문서:
  - 카카오 디벨로퍼스 문서: <https://developers.kakao.com/docs/ko>
  - 카카오 모빌리티 디벨로퍼스: <https://developers.kakaomobility.com/>
  - 카카오페이 디벨로퍼스: <https://developers.kakaopay.com/>
- 수집일: 2026-05-02
- 매뉴얼 위치: [./manuals](manuals)

## 공통 사용 방식

### 인증 키 종류

| 키 종류 | 용도 | 노출 위치 |
|---|---|---|
| **JavaScript Key** | 카카오맵 JS SDK, 카카오 로그인(JS), 카카오톡 공유(JS) | 클라이언트(브라우저) — 도메인 등록 필수 |
| **REST API Key** | 카카오 로그인 토큰 교환, 로컬, 카카오톡 메시지/채널 등 | 서버 — IP 등록 권장 / 환경변수 보관 |
| **Native App Key** | Android/iOS SDK 사용 | 모바일 앱 — 패키지/번들 ID 등록 |
| **Admin Key** | 어드민 전용 (사용자 관리 등) | 서버 전용 — 절대 노출 금지 |
| **Secret Key (카카오페이)** | 카카오페이 결제 호출 | 서버 — 가맹점 인증 |

### REST 호출 형식

대부분의 REST API는 `Authorization: KakaoAK {REST_API_KEY}` 헤더로 인증합니다. 사용자 행위(메시지 전송 등)는 추가로 `Authorization: Bearer {ACCESS_TOKEN}`을 사용합니다.

```text
GET https://dapi.kakao.com/v2/local/search/keyword.json?query=찜질방&x=129.158&y=35.158&radius=2000
Authorization: KakaoAK ${REST_API_KEY}
```

## API 목록

### 핵심 (런트립 MVP 필수)

| No | API명 | 분류 | 용도 | 매뉴얼 | 디벨로퍼스 |
|---:|---|---|---|---|---|
| 01 | 카카오맵 (JavaScript SDK) | 지도 | 대회 코스 영역, POI 마커, D-1/D-day/D+1 동선 폴리라인 시각화 | [01-카카오맵.md](manuals/01-카카오맵.md) | [docs](https://developers.kakao.com/docs/ko/kakaomap/common) |
| 02 | 카카오 로그인 | 인증 | OAuth 2.0 로그인, 액세스/리프레시 토큰, 사용자 정보 조회 | [02-카카오-로그인.md](manuals/02-카카오-로그인.md) | [docs](https://developers.kakao.com/docs/ko/kakaologin/common) |
| 03 | 카카오싱크 | 인증 | 간편가입(약관·개인정보 동의 통합) + 카카오톡 채널 친구 추가 | [03-카카오싱크.md](manuals/03-카카오싱크.md) | [docs](https://developers.kakao.com/docs/ko/kakaosync/common) |
| 04 | 카카오 모빌리티 길찾기 API | 모빌리티 | 자동차/다중경유지/미래운행 경로, 숙소↔대회장↔관광지 이동시간 | [04-카카오-모빌리티-길찾기.md](manuals/04-카카오-모빌리티-길찾기.md) | [docs](https://developers.kakaomobility.com/guide/navi-api/) |

### 추천 (런트립 MVP에서 강력 활용)

| No | API명 | 분류 | 용도 | 매뉴얼 | 디벨로퍼스 |
|---:|---|---|---|---|---|
| 05 | 로컬 (Local) | 검색·지오코딩 | 키워드/카테고리 검색(찜질방·짐보관·식당), 주소↔좌표 변환, 행정구역 변환 | [05-로컬-검색.md](manuals/05-로컬-검색.md) | [docs](https://developers.kakao.com/docs/ko/local/common) |
| 06 | 카카오내비 | 길안내 | App-to-App으로 카카오내비 앱 실행해 D-day 대회장 길안내 트리거 | [06-카카오내비.md](manuals/06-카카오내비.md) | [docs](https://developers.kakao.com/docs/ko/kakaonavi/common) |

### 발전 방향 (V2 이후 단계적 도입)

| No | API명 | 분류 | 용도 | 매뉴얼 | 디벨로퍼스 |
|---:|---|---|---|---|---|
| 07 | 카카오톡 공유 | 메시징 | 대회 카드, 추천 일정을 카톡 친구·채팅방에 공유 | [07-카카오톡-공유.md](manuals/07-카카오톡-공유.md) | [docs](https://developers.kakao.com/docs/ko/kakaotalk-share/common) |
| 08 | 카카오톡 메시지 | 메시징 | 사용자에게 D-1, 결승 직전 등 트리거 알림 발송 (나에게/친구) | [08-카카오톡-메시지.md](manuals/08-카카오톡-메시지.md) | [docs](https://developers.kakao.com/docs/ko/kakaotalk-message/common) |
| 09 | 카카오톡 채널 | 마케팅·CRM | 지자체·소상공인 채널 운영, 재방문 마케팅, 채널 친구 추가 | [09-카카오톡-채널.md](manuals/09-카카오톡-채널.md) | [docs](https://developers.kakao.com/docs/ko/kakaotalk-channel/common) |
| 10 | 카카오페이 (단건/정기) | 결제 | 향후 경량 제휴(레이트체크아웃·짐보관 패키지) 결제 | [10-카카오페이.md](manuals/10-카카오페이.md) | [docs](https://developers.kakaopay.com/docs/payment/online/common) |

## 신청·심사가 필요한 API

다음 API는 사전 신청 또는 비즈 앱 전환·검수 절차를 필요로 합니다. **MVP 일정 산정 시 반드시 별도 리드타임을 잡아둘 것.**

| API | 절차 |
|---|---|
| 카카오싱크 | 비즈 앱 전환 + 개인정보 동의항목 권한 신청·심사 |
| 카카오톡 메시지 (친구에게 보내기) | 사용 권한 신청 필수 (앱 멤버 외에는 추가 검수) |
| 카카오톡 채널 (관리자 API) | 채널 사업자 정보 일치 + 채널 연결 |
| 카카오페이 | 가맹점 가입 + Secret Key 발급 |
| 카카오 모빌리티 길찾기 | REST API 키 발급 + 트래픽 한도는 가격/문의로 별도 협의 |

## 참고 링크

- 쿼터 정책: <https://developers.kakao.com/docs/ko/getting-started/quota>
- 유료 API 설정: <https://developers.kakao.com/docs/ko/app-setting/paid-api>
- 카카오맵 리소스 다운로드: <https://developers.kakao.com/tool/resource/map>
- 모빌리티 가격/문의: <https://developers.kakaomobility.com/price/>
