"""
fix_event_types.py
==================
기존 output/races_sample.(json/csv)의 event_types를 distances 기준으로 다시 채운다.
- 기존: 풀/하프/10km/5km만 들어감.
- 변경: 15·12·28·19km 등 모든 거리를 라벨로 포함(풀/하프는 한글, 나머지는 'Nkm').
- 재크롤링 없이 기존 파일만 수정(여러 번 실행해도 안전).

사용법:  python3 fix_event_types.py
"""

from __future__ import annotations

import csv
import json

IN_JSON = "output/races_sample.json"
OUT_JSON = "output/races_sample.json"
OUT_CSV = "output/races_sample.csv"


def dist_label(d: float) -> str:
    if abs(d - 42.195) < 0.01:
        return "풀"
    if abs(d - 21.0975) < 0.001:
        return "하프"
    return f"{int(d)}km" if float(d).is_integer() else f"{d}km"


def save_json(data: list[dict], path: str):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def save_csv(data: list[dict], path: str):
    if not data:
        return
    fieldnames = list(data[0].keys())
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in data:
            row = {}
            for k, v in r.items():
                if isinstance(v, list):
                    row[k] = json.dumps(v, ensure_ascii=False)
                elif v is None:
                    row[k] = ""
                else:
                    row[k] = v
            w.writerow(row)


def main():
    with open(IN_JSON, encoding="utf-8") as f:
        data = json.load(f)

    changed = 0
    for r in data:
        dist = r.get("distances") or []
        new_et = [dist_label(d) for d in dist]
        if new_et != (r.get("event_types") or []):
            changed += 1
        r["event_types"] = new_et

    save_json(data, OUT_JSON)
    save_csv(data, OUT_CSV)

    print(f"event_types 갱신 완료: 총 {len(data)}건 중 {changed}건 변경")
    print("저장:", OUT_JSON, "/", OUT_CSV)
    # 변경 예시 몇 개
    shown = 0
    for r in data:
        if r.get("distances") and len(r["event_types"]) >= 3:
            print(f"  예) {r['name'][:22]:24} {r['distances']} → {r['event_types']}")
            shown += 1
            if shown >= 4:
                break


if __name__ == "__main__":
    main()
