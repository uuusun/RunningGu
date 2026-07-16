# VisitKorea OpenAPI 카탈로그

## 기준

- 기준 페이지: [한국관광콘텐츠랩 API 활용연습](https://api.visitkorea.or.kr/#/useUtilExercises)
- 원본 데이터: [../../data/api/visitkorea-use-util-exercises.json](../../data/api/visitkorea-use-util-exercises.json)
- 매뉴얼 첨부 위치: [../../assets/api-manuals](../../assets/api-manuals)
- 수집일: 2026-04-23

공식 활용연습 목록에는 총 27개 API가 있으며, 현재 목록상 `apiType`은 모두 `REST`입니다. `category` 기준으로는 `국내여행` 25개, `국내관광` 2개입니다.

## 공통 사용 방식

대부분 data.go.kr OpenAPI 신청 후 발급받은 인증키로 REST URL을 호출합니다.

```text
https://apis.data.go.kr/{기관/서비스}/{서비스명}/{오퍼레이션}?serviceKey={SERVICE_KEY}&numOfRows=10&pageNo=1&MobileOS=ETC&MobileApp={APP_NAME}&_type=json
```

공통 파라미터는 API별 매뉴얼마다 다를 수 있지만, 한국관광공사 예시는 보통 `serviceKey`, `numOfRows`, `pageNo`, `MobileOS`, `MobileApp`, `_type=json` 형태를 사용합니다. JSON 응답을 원하면 `_type=json`을 명시합니다.

## 추출 매뉴얼 Markdown

ZIP 원본을 압축 해제한 뒤 DOCX 본문과 XLSX 시트 일부를 API별 Markdown으로 변환했습니다. 바이브 코딩 중 API 파라미터, 오퍼레이션명, 코드표를 검색할 때는 아래 파일을 우선 확인하면 됩니다.

- [01-한국관광공사_국문-관광정보-서비스](manuals/01-한국관광공사_국문-관광정보-서비스.md)
- [02-한국관광공사_영문-관광정보서비스](manuals/02-한국관광공사_영문-관광정보서비스.md)
- [03-한국관광공사_중문-간체-관광정보서비스](manuals/03-한국관광공사_중문-간체-관광정보서비스.md)
- [04-한국관광공사_중문-번체-관광정보서비스](manuals/04-한국관광공사_중문-번체-관광정보서비스.md)
- [05-한국관광공사_일문-관광정보서비스](manuals/05-한국관광공사_일문-관광정보서비스.md)
- [06-한국관광공사_독어-관광정보서비스](manuals/06-한국관광공사_독어-관광정보서비스.md)
- [07-한국관광공사_불어-관광정보서비스](manuals/07-한국관광공사_불어-관광정보서비스.md)
- [08-한국관광공사_서어-관광정보서비스](manuals/08-한국관광공사_서어-관광정보서비스.md)
- [09-한국관광공사_노어-관광정보서비스](manuals/09-한국관광공사_노어-관광정보서비스.md)
- [11-한국관광공사_무장애-여행-정보](manuals/11-한국관광공사_무장애-여행-정보.md)
- [12-한국관광공사_생태-관광-정보](manuals/12-한국관광공사_생태-관광-정보.md)
- [13-한국관광공사_관광사진-정보](manuals/13-한국관광공사_관광사진-정보.md)
- [14-한국관광공사_고캠핑-정보-조회서비스](manuals/14-한국관광공사_고캠핑-정보-조회서비스.md)
- [15-한국관광공사_관광지-오디오-가이드정보](manuals/15-한국관광공사_관광지-오디오-가이드정보.md)
- [16-한국관광공사_관광빅데이터-정보서비스](manuals/16-한국관광공사_관광빅데이터-정보서비스.md)
- [17-한국관광공사_두루누비-정보-서비스](manuals/17-한국관광공사_두루누비-정보-서비스.md)
- [19-한국관광공사_관광인_채용정보_서비스](manuals/19-한국관광공사_관광인_채용정보_서비스.md)
- [20-한국관광공사_관광지-집중률-방문자-추이-예측-정보](manuals/20-한국관광공사_관광지-집중률-방문자-추이-예측-정보.md)
- [21-한국관광공사_기초지자체-중심-관광지-정보](manuals/21-한국관광공사_기초지자체-중심-관광지-정보.md)
- [22-한국관광공사_관광지별-연관-관광지-정보](manuals/22-한국관광공사_관광지별-연관-관광지-정보.md)
- [23-한국관광공사_반려동물_동반여행_서비스](manuals/23-한국관광공사_반려동물_동반여행_서비스.md)
- [24-한국관광공사_의료관광정보](manuals/24-한국관광공사_의료관광정보.md)
- [25-한국관광공사_웰니스관광정보](manuals/25-한국관광공사_웰니스관광정보.md)
- [26-한국관광공사_관광공모전-사진-수상작-정보](manuals/26-한국관광공사_관광공모전-사진-수상작-정보.md)
- [27-한국관광공사_지역별-관광-다양성](manuals/27-한국관광공사_지역별-관광-다양성.md)
- [28-한국관광공사_지역별-관광-수요-강도](manuals/28-한국관광공사_지역별-관광-수요-강도.md)
- [29-한국관광공사_지역별-관광-자원-수요](manuals/29-한국관광공사_지역별-관광-자원-수요.md)

## API 목록

| No | API 소개 | API 타입 | API 구분 | 매뉴얼 | 활용신청 |
| ---: | --- | --- | --- | --- | --- |
| 1 | 한국관광공사_국문 관광정보 서비스: 코드조회, 통합/상세검색, 위치/지역 기반 국내 관광정보를 국문으로 제공 | REST | 국내여행 | [1737596499508.zip](../../assets/api-manuals/1737596499508.zip) | [data.go.kr](https://www.data.go.kr/data/15101578/openapi.do) |
| 2 | 한국관광공사_영문 관광정보서비스: 국내 관광정보를 영문으로 제공 | REST | 국내여행 | [1737596531873.zip](../../assets/api-manuals/1737596531873.zip) | [data.go.kr](https://www.data.go.kr/data/15101753/openapi.do) |
| 3 | 한국관광공사_중문 간체 관광정보서비스: 국내 관광정보를 중문 간체로 제공 | REST | 국내여행 | [1704160495049.zip](../../assets/api-manuals/1704160495049.zip) | [data.go.kr](https://www.data.go.kr/data/15101764/openapi.do) |
| 4 | 한국관광공사_중문 번체 관광정보서비스: 국내 관광정보를 중문 번체로 제공 | REST | 국내여행 | [1737596423271.zip](../../assets/api-manuals/1737596423271.zip) | [data.go.kr](https://www.data.go.kr/data/15101769/openapi.do) |
| 5 | 한국관광공사_일문 관광정보서비스: 국내 관광정보를 일문으로 제공 | REST | 국내여행 | [1737596480579.zip](../../assets/api-manuals/1737596480579.zip) | [data.go.kr](https://www.data.go.kr/data/15101760/openapi.do) |
| 6 | 한국관광공사_독어 관광정보서비스: 국내 관광정보를 독어로 제공 | REST | 국내여행 | [1737596457504.zip](../../assets/api-manuals/1737596457504.zip) | [data.go.kr](https://www.data.go.kr/data/15101805/openapi.do) |
| 7 | 한국관광공사_불어 관광정보서비스: 국내 관광정보를 불어로 제공 | REST | 국내여행 | [1737596408255.zip](../../assets/api-manuals/1737596408255.zip) | [data.go.kr](https://www.data.go.kr/data/15101808/openapi.do) |
| 8 | 한국관광공사_서어 관광정보서비스: 국내 관광정보를 서어로 제공 | REST | 국내여행 | [1737596391866.zip](../../assets/api-manuals/1737596391866.zip) | [data.go.kr](https://www.data.go.kr/data/15101811/openapi.do) |
| 9 | 한국관광공사_노어 관광정보서비스: 국내 관광정보를 노어로 제공 | REST | 국내여행 | [1737596057411.zip](../../assets/api-manuals/1737596057411.zip) | [data.go.kr](https://www.data.go.kr/data/15101831/openapi.do) |
| 11 | 한국관광공사_무장애 여행 정보: 장애인, 어르신, 영유아 동반 여행을 위한 무장애 관광정보 제공 | REST | 국내여행 | [1737596514908.zip](../../assets/api-manuals/1737596514908.zip) | [data.go.kr](https://www.data.go.kr/data/15101897/openapi.do) |
| 12 | 한국관광공사_생태 관광 정보: 친환경관광 및 지역경제 활성화를 위한 공정관광 정보 제공 | REST | 국내관광 | [1704160406003.zip](../../assets/api-manuals/1704160406003.zip) | [data.go.kr](https://www.data.go.kr/data/15101908/openapi.do) |
| 13 | 한국관광공사_관광사진 정보: 관광사진갤러리의 사진 제목, 촬영장소, 촬영일 등 제공 | REST | 국내관광 | [1704160396374.zip](../../assets/api-manuals/1704160396374.zip) | [data.go.kr](https://www.data.go.kr/data/15101914/openapi.do) |
| 14 | 한국관광공사_고캠핑 정보 조회서비스: 고캠핑 홈페이지의 캠핑장 정보 제공 | REST | 국내여행 | [1704160387374.zip](../../assets/api-manuals/1704160387374.zip) | [data.go.kr](https://www.data.go.kr/data/15101933/openapi.do) |
| 15 | 한국관광공사_관광지 오디오 가이드정보: 오디(odii)의 음성, 대본, 사진정보 제공 | REST | 국내여행 | [1720672146251.zip](../../assets/api-manuals/1720672146251.zip) | [data.go.kr](https://www.data.go.kr/data/15101971/openapi.do) |
| 16 | 한국관광공사_관광빅데이터 정보서비스: 데이터랩의 광역/기초지자체별 방문자수 등 관광 빅데이터 제공 | REST | 국내여행 | [1704160370032.zip](../../assets/api-manuals/1704160370032.zip) | [data.go.kr](https://www.data.go.kr/data/15101972/openapi.do) |
| 17 | 한국관광공사_두루누비 정보 서비스: 걷기, 자전거 등 레저여행 길/코스 정보 제공 | REST | 국내여행 | [1704160359411.zip](../../assets/api-manuals/1704160359411.zip) | [data.go.kr](https://www.data.go.kr/data/15101974/openapi.do) |
| 19 | 한국관광공사_관광인_채용정보_서비스: 관광전문인력포털 관광인의 채용정보 제공 | REST | 국내여행 | [1704160822554.zip](../../assets/api-manuals/1704160822554.zip) | [data.go.kr](https://www.data.go.kr/data/15125070/openapi.do) |
| 20 | 한국관광공사_관광지 집중률 방문자 추이 예측 정보: 조회일 기준 향후 30일 관광객 집중률 예측 정보 제공 | REST | 국내여행 | [1725501618773.zip](../../assets/api-manuals/1725501618773.zip) | [data.go.kr](https://www.data.go.kr/data/15128555/openapi.do) |
| 21 | 한국관광공사_기초지자체 중심 관광지 정보: 타 관광지와 많이 연결되는 중심 관광지 100위 정보 제공 | REST | 국내여행 | [1725501897980.zip](../../assets/api-manuals/1725501897980.zip) | [data.go.kr](https://www.data.go.kr/data/15128559/openapi.do) |
| 22 | 한국관광공사_관광지별 연관 관광지 정보: 중심관광지와 연결성이 높은 연관관광지 정보 제공 | REST | 국내여행 | [1725502022236.zip](../../assets/api-manuals/1725502022236.zip) | [data.go.kr](https://www.data.go.kr/data/15128560/openapi.do) |
| 23 | 한국관광공사_반려동물_동반여행_서비스: 반려동물 동반 가능 관광지, 문화시설, 숙박, 음식점 등 제공 | REST | 국내여행 | [1737596366080.zip](../../assets/api-manuals/1737596366080.zip) | [data.go.kr](https://www.data.go.kr/data/15135102/openapi.do) |
| 24 | 한국관광공사_의료관광정보: 지역/위치 기반 국내 의료 관광정보 제공 | REST | 국내여행 | [1725080563660.zip](../../assets/api-manuals/1725080563660.zip) | [data.go.kr](https://www.data.go.kr/data/15143913/openapi.do) |
| 25 | 한국관광공사_웰니스관광정보: 지역/위치 기반 국내 웰니스 관광정보 제공 | REST | 국내여행 | [1725080513010.zip](../../assets/api-manuals/1725080513010.zip) | [data.go.kr](https://www.data.go.kr/data/15144030/openapi.do) |
| 26 | 한국관광공사_관광공모전(사진) 수상작 정보: 사진 부문 수상작 제목, 촬영일, 촬영지, 키워드, 이미지 정보 제공 | REST | 국내여행 | [1725092509540.zip](../../assets/api-manuals/1725092509540.zip) | [data.go.kr](https://www.data.go.kr/data/15145706/openapi.do) |
| 27 | 한국관광공사_지역별 관광 다양성: 관광객/소비/국제적 다양성 등 관광 다양성 지표 제공 | REST | 국내여행 | [manual_areaTarDivService.zip](../../assets/api-manuals/manual_areaTarDivService.zip) | [data.go.kr](https://www.data.go.kr/data/15151365/openapi.do) |
| 28 | 한국관광공사_지역별 관광 수요 강도: 관광 체류 강도, 관광 소비 강도 등 수요 강도 지표 제공 | REST | 국내여행 | [manual_areaTarDemDsService.zip](../../assets/api-manuals/manual_areaTarDemDsService.zip) | [data.go.kr](https://www.data.go.kr/data/15151868/openapi.do) |
| 29 | 한국관광공사_지역별 관광 자원 수요: 관광 서비스 수요, 문화 자원 수요 등 수요 지표 제공 | REST | 국내여행 | [manual_areaTarResDemService.zip](../../assets/api-manuals/manual_areaTarResDemService.zip) | [data.go.kr](https://www.data.go.kr/data/15152138/openapi.do) |

## API 후보 선정 메모

- 가장 안정적인 기본 데이터는 `국문 관광정보 서비스`입니다.
- 공모전 차별화에는 `반려동물 동반여행`, `무장애 여행`, `관광지 집중률 예측`, `지역별 관광 수요 지표`가 좋습니다.
- 사진/콘텐츠 품질을 높이려면 `관광사진 정보`와 `관광공모전 사진 수상작 정보`를 보조 API로 붙이는 구성이 좋습니다.
