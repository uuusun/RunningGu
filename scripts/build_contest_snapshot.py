#!/usr/bin/env python3
"""data/races_sample.csv → data/contest_snapshot.json (서버용 대회 스냅샷)

계약: docs/contest-snapshot-contract.md (SPEC §8.2 · 결정-39·40)
- 정규화·병합 규칙은 build_races_json.py 의 함수를 그대로 import 한다 — 병합 책임 단일화(결정-39)
- 같은 입력 → 같은 UTF-8 산출물. 타임스탬프는 전부 입력(crawled_at 등)에서 파생한다
- 좌표 없는 원천이 생기면 실패한다 → add_coordinates.py 를 먼저 돌린다 (D-09)

사용: python scripts/build_contest_snapshot.py [--src 경로] [--out 경로]
"""
import csv
import hashlib
import io
import json
import re
import sys
import unicodedata
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

from build_races_json import REGIONS, merge_group, norm_name, region_of, std_events

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "data" / "races_sample.csv"
OUT = ROOT / "data" / "contest_snapshot.json"

SCHEMA_VERSION = 1

# API 명세 §3 · 부록 enum 매핑
SOURCE_TYPE = {"마라톤GO": "MARATHON_GO", "마라톤온라인": "MARATHON_ONLINE"}
EVENT_ENUM = {"풀": "FULL", "하프": "HALF", "10K": "K10", "5K": "K5"}
EVENT_ENUM_ORDER = ["FULL", "HALF", "K10", "K5"]
REG_STATUS = {"접수중": "OPEN", "마감": "CLOSED", "접수전": "BEFORE", "미정": "UNKNOWN", "": "UNKNOWN"}
CATEGORIES = {"로드", "트레일", "걷기", "야간"}
# 개인정보 미보존 원칙 — rawPayload 에서 제외 (계약 §2.3).
# description 은 자유 텍스트에 담당자 실명·휴대폰·이메일이 섞여 있어 통째로 뺀다.
# canonical(§6.2)과 공개 API(§3) 어디에도 description 을 쓰는 곳이 없다.
RAW_EXCLUDE = {"contact_email", "contact_phone", "description"}

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
TIME_RE = re.compile(r"^\d{2}:\d{2}$")


def nfc(s):
    return unicodedata.normalize("NFC", s) if isinstance(s, str) else s


def opt(s):
    """빈 문자열 → null (서버 nullable 필드용)."""
    s = (s or "").strip()
    return s or None


def opt_date(s, label, name, warnings):
    s = (s or "").strip()
    if not s:
        return None
    if DATE_RE.match(s):
        return s
    warnings.append(f"{label} 형식 불일치 → null: {name} ({s!r})")
    return None


def kst_to_utc(naive_kst):
    """CSV 의 naive KST(예: 2026-06-14T20:38:13) → UTC 'Z' 문자열 (계약 §2.3)."""
    dt = datetime.fromisoformat(naive_kst.strip())
    return (dt - timedelta(hours=9)).strftime("%Y-%m-%dT%H:%M:%SZ")


def fail(errors):
    print(f"오류 {len(errors)}건 — 스냅샷을 만들지 않는다:", file=sys.stderr)
    for e in errors[:20]:
        print(" -", e, file=sys.stderr)
    sys.exit(1)


def to_source(row):
    return {
        "sourceType": SOURCE_TYPE[row["source"]],
        "externalId": row["race_id"],
        "sourceUrl": (row.get("detail_url") or "").strip(),
        "fetchedAt": kst_to_utc(row["crawled_at"]),
        "lastCheckedDate": (row.get("last_checked") or "").strip(),
        "rawPayload": {k: v for k, v in row.items() if k not in RAW_EXCLUDE},
    }


def to_contest(base, grp, warnings, errors):
    name = (base.get("name") or "").strip()
    date = (base.get("event_date") or "").strip()
    region = region_of(base, warnings)
    if region not in REGIONS:
        errors.append(f"region 미해결: {name} ({date}) — {region!r}")
    category = (base.get("category") or "").strip()
    if category not in CATEGORIES:
        errors.append(f"category 비표준: {name} ({date}) — {category!r}")
    try:
        lat, lng = float(base["latitude"]), float(base["longitude"])
        # Importer 검증(계약 §4-4)과 같은 범위를 생산자 쪽에서 먼저 잡는다 — 적재 실패 후에야 알게 되는 걸 막는다
        if not (33.0 <= lat <= 39.0 and 124.0 <= lng <= 132.0):
            errors.append(f"좌표 범위 밖: {name} ({date}) — ({lat}, {lng})")
    except (ValueError, KeyError, TypeError):
        lat = lng = None
        errors.append(f"좌표 없음: {name} ({date}) — add_coordinates.py 를 먼저 돌린다 (D-09)")

    start_time = opt(base.get("start_time"))
    if start_time and not TIME_RE.match(start_time):
        warnings.append(f"startTime 형식 불일치 → null: {name} ({start_time!r})")
        start_time = None

    sources = sorted((to_source(r) for r in grp), key=lambda s: (s["sourceType"], s["externalId"]))
    return {
        "canonicalKey": f"{date}|{norm_name(name)}",
        "name": name,
        "region": region,
        "place": (base.get("venue") or "").strip(),
        "roadAddress": opt(base.get("road_address")),
        "contestDate": date,
        "startTime": start_time,
        "events": [EVENT_ENUM[e] for e in std_events(base)],
        "category": category,
        "applyStart": opt_date(base.get("reg_start"), "applyStart", name, warnings),
        "applyEnd": opt_date(base.get("reg_end"), "applyEnd", name, warnings),
        "regStatusFallback": REG_STATUS.get((base.get("reg_status") or "").strip(), "UNKNOWN"),
        "organizer": opt(base.get("organizer")),
        "officialUrl": opt(base.get("official_url")),
        "detailUrl": opt(base.get("detail_url")),
        "imageUrl": opt(base.get("image_url")),
        "lat": lat,
        "lng": lng,
        "checkedAt": max(s["fetchedAt"] for s in sources),
        "sources": sources,
    }


def main():
    src_path, out_path = SRC, OUT
    if "--src" in sys.argv:
        src_path = Path(sys.argv[sys.argv.index("--src") + 1])
    if "--out" in sys.argv:
        out_path = Path(sys.argv[sys.argv.index("--out") + 1])
    src_path = src_path.resolve()  # 상대경로 입력에도 meta.source 가 저장소 상대 경로로 나오게

    raw_bytes = src_path.read_bytes()
    # newline="" — RFC 4180 따옴표 안 멀티라인 필드를 원문 그대로 보존 (build_races_json.py 와 동일)
    rows = [
        {k: nfc(v) for k, v in r.items()}
        for r in csv.DictReader(io.StringIO(raw_bytes.decode("utf-8-sig"), newline=""))
    ]

    warnings, errors = [], []
    skipped = [r for r in rows if not (r.get("name") or "").strip() or not (r.get("event_date") or "").strip()]
    kept = [r for r in rows if r not in skipped]

    for r in kept:
        if r["source"] not in SOURCE_TYPE:
            errors.append(f"미지원 source: {r['source']!r} ({r.get('race_id')})")
        if not DATE_RE.match(r["event_date"]):
            errors.append(f"event_date 형식 불일치: {r.get('name')} ({r['event_date']!r})")
        try:
            kst_to_utc(r.get("crawled_at") or "")
        except ValueError:
            errors.append(f"crawled_at 형식 불일치: {r.get('race_id')} ({r.get('crawled_at')!r})")
        if not DATE_RE.match((r.get("last_checked") or "").strip()):
            errors.append(f"last_checked 형식 불일치: {r.get('race_id')} ({r.get('last_checked')!r})")
    if errors:
        fail(errors)  # 아래 직렬화 단계에서 KeyError/ValueError 로 죽기 전에 목록으로 보고한다
    if not kept:
        fail(["canonical 0건 — 입력에 유효한 행이 없다 (크롤 실패 여부를 확인한다)"])

    groups = defaultdict(list)
    for r in kept:
        groups[(norm_name(r["name"]), r["event_date"])].append(r)

    contests = []
    for grp in groups.values():
        base, _sources_label, _checked = merge_group(grp)
        contests.append(to_contest(base, grp, warnings, errors))
    contests.sort(key=lambda c: (c["contestDate"], c["canonicalKey"]))

    # 유일키 검증 (계약 §4-2 를 생산자 쪽에서도 보장)
    keys = [c["canonicalKey"] for c in contests]
    if len(set(keys)) != len(keys):
        errors.append("canonicalKey 중복: " + str([k for k in keys if keys.count(k) > 1][:3]))
    src_keys = [(s["sourceType"], s["externalId"]) for c in contests for s in c["sources"]]
    if len(set(src_keys)) != len(src_keys):
        errors.append("(sourceType, externalId) 중복: " + str([k for k in src_keys if src_keys.count(k) > 1][:3]))

    if errors:
        fail(errors)

    snapshot = {
        "schemaVersion": SCHEMA_VERSION,
        "meta": {
            "source": src_path.relative_to(ROOT).as_posix() if src_path.is_relative_to(ROOT) else src_path.name,
            "sourceSha256": hashlib.sha256(raw_bytes).hexdigest(),
            "sourceRowCount": len(rows),
            "canonicalCount": len(contests),
            "sourceRecordCount": sum(len(c["sources"]) for c in contests),
            "skipped": [
                {"externalId": r.get("race_id") or "", "reason": "MISSING_REQUIRED"} for r in skipped
            ],
            "checkedAtMax": max(s["fetchedAt"] for c in contests for s in c["sources"]),
        },
        "contests": contests,
    }
    assert snapshot["meta"]["sourceRecordCount"] == len(kept)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(snapshot, f, ensure_ascii=False, indent=2)
        f.write("\n")

    m = snapshot["meta"]
    print(f"입력 {m['sourceRowCount']}행 → canonical {m['canonicalCount']}건 · 원천 {m['sourceRecordCount']}건 · 스킵 {len(m['skipped'])}건")
    print("종목없음:", sum(1 for c in contests if not c["events"]),
          "| 복수출처:", sum(1 for c in contests if len(c["sources"]) > 1),
          "| checkedAtMax:", m["checkedAtMax"])
    print(f"출력: {out_path} ({out_path.stat().st_size // 1024}KB)")
    if warnings:
        print(f"\n경고 {len(warnings)}건:")
        for w in warnings[:15]:
            print(" -", w)


if __name__ == "__main__":
    main()
