# 원스토어 제출 첨부 자료

이 폴더는 `docs/store/store-listing.md`의 원스토어 등록 초안에서 이어지는 실제 제출 첨부 자료를 둔다.

## 지식재산권

- 원스토어 기본정보의 지식재산권 사용 여부는 `예`로 답한다.
- 이는 침해를 인정한다는 뜻이 아니라, 앱이 외부 데이터와 오픈소스 글꼴을 적법한 이용 조건 안에서 사용한다는 뜻이다.
- 원스토어 제출 파일: `intellectual-property-evidence-final.pdf`
- 기존 `intellectual-property-evidence.pdf`는 작성 과정의 원본이며 원스토어에 제출하지 않는다.

PDF는 다음 근거를 한 문서로 묶는다.

- 한국관광공사 관광정보 API, 두루누비, 웰니스 관광정보 활용 근거
- 한국관광공사 상세 기능별 일일 트래픽 1,000 확인 화면
- 카카오 Developers 앱 등록 및 지도/로컬 API 이용 근거
- 카카오 로그인, 지도, 로컬 API 제품 설정 근거
- OpenStreetMap ODbL 조건 및 앱 내 `© OpenStreetMap contributors` 표시
- 한국등산·트레킹지원센터 데이터 이용 조건
- Pretendard, Archivo의 SIL Open Font License 1.1
- 앱 아이콘, 로고, 앱 내 문구의 자체 제작 확인
- 앱 러닝코스 화면의 Kakao 지도 워터마크 표시 캡처
- 공공데이터포털 국문 관광정보, 두루누비, 웰니스 관광정보 승인/상세 캡처
- Kakao Developers Android 앱 등록, REST API 키, 카카오 로그인, 카카오맵 설정 캡처
- 한국등산트레킹지원센터 공공데이터 이용 근거 캡처
- 앱 내 OpenStreetMap 출처 표시 캡처

## 최종 제출본

최종 PDF에는 준비한 승인·설정·이용조건 화면 원본을 반영했다.

Kakao 앱 키 실제 값은 PDF에 적지 않고 마스킹본만 사용한다.
확인서에는 제출자 `유선경`, 확인일 `2026-09-13`을 기입했다.
위 계정과 이용권한은 루트메이트가 런닝구 운영 목적으로 관리한다.

`evidence/kakao-map-watermark.png`는 에뮬레이터에서 런닝구 앱의 러닝코스 화면을 열어 캡처한
카카오 지도 표시 확인 이미지다. 개인 정보나 키는 포함하지 않는다.

`evidence/카카오디벨로퍼스_마스킹.png`는 `evidence/카카오디벨로퍼스.png`에서 네이티브 앱 키 값을
가린 제출용 이미지다. PDF에는 마스킹본만 반영한다.

`evidence/REST API_마스킹.png`는 `evidence/REST API.png`에서 REST API 키와 클라이언트 시크릿 값을
가린 제출용 이미지다. PDF에는 마스킹본만 반영한다.

`evidence/한국등산트레킹지원센터_국가숲길코스.png`와
`evidence/한국등산트레킹지원센터_산림청100대명산.png`는 공공데이터포털에서 데이터셋명,
제공기관, 활용 설명, `이용허락범위 제한 없음`을 함께 확인할 수 있도록 캡처한 최종 증빙이다.

`evidence/한국등산트레킹지원센터.png`와 `evidence/한국등산트레킹지원센터2.png`는
기관 소개와 목록 확인용 참고 자료이며 최종 PDF에는 사용하지 않는다.

`evidence/앱_OSM_출처표시.png`는 staging APK에서 러닝코스 출발지 주변 목록 하단의
`© OpenStreetMap contributors` 표시를 캡처한 제출용 이미지다.

## 판매정보 스크린샷

`screenshots/`에는 `runninggu-production-8fbd0bf9.apk`를 전용 AVD에 설치해 촬영한
원스토어 판매정보용 화면 8장을 둔다. 모두 720×1280 PNG이며 장당 1MB 이하다.

- `01-home.png`: 마감 임박 대회와 동선 만들기 CTA
- `02-calendar-filter.png`: 거리·모집 상태·지역 필터
- `03-race-detail.png`: 대회 상세와 동선 만들기 CTA
- `04-festivals.png`: 축제·지역 관광 추천과 한국관광공사 출처
- `05-itinerary-preferences.png`: 종목·회복 강도·여행 취향 기반 동선 설정
- `06-running-course-list.png`: 전국 261개 러닝·산책 코스와 지역별 코스 수
- `07-itinerary-lodging.png`: 카카오 로컬 기반 대회장 주변 숙소 선택
- `08-itinerary-result-map.png`: 지도 경로선·번호 마커와 날짜별 추천 동선

전용 x86_64 AVD에서는 APK를 기본 설치하면 앱 ABI가 x86_64로 선택되어 ARM 전용 카카오 지도
라이브러리를 열지 못했다. 같은 릴리스 APK를 `--abi arm64-v8a`로 재설치해 ARM 번역 실행한 뒤
지도 인증 응답과 지도·경로선 표시를 확인하고 8번 화면을 촬영했다.
