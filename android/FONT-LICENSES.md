# 번들 폰트 라이선스

앱에 포함된 폰트와 출처·라이선스. 목업 v2(`docs/mockup-design/런닝구-목업-v2.html`)의
`--font-body` / `--font-num` 지정을 따른 것이다.

| 파일 | 폰트 | 용도 | 라이선스 | 출처 |
|---|---|---|---|---|
| `app/src/main/res/font/pretendard_variable.ttf` | Pretendard Variable v1.3.9 | 본문 (`--font-body`) | SIL Open Font License 1.1 | https://github.com/orioncactus/pretendard |
| `app/src/main/res/font/archivo_variable.ttf` | Archivo Variable | 숫자·영문 라벨 (`--font-num`) | SIL Open Font License 1.1 | https://github.com/google/fonts/tree/main/ofl/archivo |

두 폰트 모두 OFL 1.1이라 앱 번들·배포에 제약이 없다. 다만 OFL은 **저작권·라이선스 고지**를
요구하므로, 스토어 배포 전 앱 내 "오픈소스 라이선스" 화면에 위 내용을 넣어야 한다.

## 용량 메모

Pretendard Variable은 한글 전체 글리프를 담아 약 6.7MB다. APK 용량이 문제가 되면:

1. `pyftsubset`으로 KS X 1001 상용 한글 2,350자 + 라틴으로 서브셋 (약 1~2MB로 감소)
2. Android App Bundle의 asset delivery 사용
3. 정적 두께 3종(400/600/800)만 번들

MVP에서는 가변 폰트 전체를 쓰고, 스토어 제출 전에 1번을 검토한다.
