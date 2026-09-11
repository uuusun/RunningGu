# 탈퇴 데이터 WAL 복구 리허설 — 2026-09-11

## 목적과 격리 조건

#227과 약관 활성화 조건에 남아 있던 다음 흐름을 운영 EC2에서 검증했다.

> 합성 사용자 생성 → 전체 백업 → 사용자 탈퇴 → 백업 시점 복원 → 최신 WAL 적용 복원 →
> 탈퇴 사용자가 되살아나지 않는지 확인

- 대상 호스트: `i-0c9f040b65d41d6c1` (`ap-northeast-2`)
- 실행: AWS Systems Manager `AWS-RunShellScript`
- 리허설 명령 ID: `665b81d3-e051-44be-a153-8576cdf126c4`
- 파일 무결성: CloudShell 업로드본 SHA-256
  `c59ca013f6e0da6664acc05f15ca1698f156abf3772cb51f74bed99c9055dedf`
- 데이터: `.invalid` 도메인의 합성 이메일과 합성 식별자만 사용
- 격리: 임시 PostgreSQL 컨테이너에 `--network none`, 메모리 512MiB, CPU 0.5 제한 적용
- 저장소: 로컬 임시 pgBackRest repository와 임시 Docker volume만 사용
- 운영 보호: `backend_runninggu-postgres-data`를 마운트하거나 운영 DB에 SQL을 실행하지 않음

## 결과

백업 label은 `20260911-032707F`다. 사용자 삭제에는 외래 키 `ON DELETE CASCADE`를 적용해
동의와 저장 코스가 함께 삭제되는 실제 탈퇴 경계를 재현했다.

| 단계 | 사용자 | 동의 | 저장 코스 | 판정 |
|---|---:|---:|---:|---|
| 전체 백업 직전 | 1 | 3 | 1 | 합성 데이터 준비 확인 |
| 탈퇴와 WAL switch 직후 | 0 | 0 | 0 | 원본 삭제 확인 |
| 백업 시점 즉시 복원 | 1 | 3 | 1 | WAL을 적용하지 않으면 탈퇴 사용자가 되살아남 |
| 최신 WAL까지 적용한 복원 | 0 | 0 | 0 | 탈퇴 상태 유지, `pg_is_in_recovery()=false` |

스크립트의 최종 판정은 `RESULT passed=true`였다. 일부러 최신 WAL을 적용하지 않은 즉시
복원에서는 사용자 1건·동의 3건·저장 코스 1건이 남았고, 최신 복원에서만 모두 0건이 됐다.
따라서 검사가 복원 결과와 무관하게 통과하는 형태가 아님을 함께 확인했다.

## 운영 자원 확인

리허설 직후 별도 점검 명령 `9647c46f-832f-4244-afdb-db0193c31710`으로 다음을 확인했다.

- 운영 PostgreSQL 컨테이너 `backend-postgres-1`: `running`
- 운영 volume: `backend_runninggu-postgres-data`
- 리허설 자원은 `rg-agreement-drill-20260911*` 이름에만 존재

확인 뒤 정리 명령 `27bed240-3146-4558-9c4e-5439c50f1d71`로 리허설 컨테이너 4개,
volume 8개와 `/tmp/rg-agreement-drill-20260911*` 경로를 삭제했다. 삭제 명령 출력에서
`REMAINING_CONTAINERS=0`, `REMAINING_VOLUMES=0`을 확인했고, CloudShell에 올린 임시 스크립트
2개도 삭제해 `CLOUDSHELL_TEMP_FILES=0`을 확인했다. 정리 뒤에도 운영 컨테이너는 `running`,
운영 volume은 `backend_runninggu-postgres-data`였다.
