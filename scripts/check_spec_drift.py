#!/usr/bin/env python3
"""바뀐 코드가 인용한 SPEC 절을 뽑아, 명세를 함께 고쳤는지 확인한다.

코드에는 `SPEC §5.1` 같은 참조가 주석으로 달려 있다. 그 줄을 건드렸다는 건
해당 절의 동작을 만졌을 가능성이 있다는 뜻이다. 그런데 SPEC.md 는 그대로라면
명세와 구현이 갈라진다 — 나중에 합칠 때 시간을 다 잡아먹는 그 문제다.

막지는 않는다. 리팩터링처럼 명세가 안 바뀌는 변경도 많다.
대신 **어느 절을 확인해야 하는지** 짚어 준다.

사용법:
    python scripts/check_spec_drift.py <base-ref> <head-ref>
"""

from __future__ import annotations

import re
import subprocess
import sys

# 코드 주석의 SPEC 참조. `SPEC §5.1` `SPEC §4.4-3` `SPEC §3-5` 를 모두 잡는다.
SPEC_REF = re.compile(r"SPEC\s*§\s*([0-9]+(?:[.\-][0-9a-zA-Z]+)*)")

# 이 경로들이 바뀌면 명세 동기화 대상으로 본다.
CODE_PREFIXES = ("android/app/src/",)

# 이 중 하나라도 바뀌었으면 "명세를 같이 고쳤다" 로 본다.
DOC_PATHS = ("SPEC.md", "docs/files/", "docs/android-porting-plan.md", "docs/domain-logic-audit.md")


def run(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def changed_files(base: str, head: str) -> list[str]:
    out = run("git", "diff", "--name-only", f"{base}...{head}")
    return [line for line in out.splitlines() if line]


def added_spec_refs(base: str, head: str) -> dict[str, set[str]]:
    """추가·수정된 줄에서 SPEC 참조를 뽑는다. {절: {파일, ...}}"""
    diff = run("git", "diff", "--unified=0", f"{base}...{head}", "--", *CODE_PREFIXES)
    refs: dict[str, set[str]] = {}
    current = ""
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
        elif line.startswith("+") and not line.startswith("+++"):
            for section in SPEC_REF.findall(line):
                refs.setdefault(section, set()).add(current)
    return refs


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    base, head = sys.argv[1], sys.argv[2]

    files = changed_files(base, head)
    if not any(f.startswith(CODE_PREFIXES) for f in files):
        print("코드 변경 없음 — 검사 생략")
        return 0

    docs_touched = [f for f in files if f.startswith(DOC_PATHS)]
    refs = added_spec_refs(base, head)

    if not refs:
        print("바뀐 줄에 SPEC 참조 없음 — 확인할 절 없음")
        return 0

    sections = sorted(refs, key=lambda s: [int(p) if p.isdigit() else p for p in re.split(r"[.\-]", s)])
    listing = "\n".join(f"  §{s}  ← {', '.join(sorted(refs[s]))}" for s in sections)

    if docs_touched:
        print(f"명세를 함께 고쳤다: {', '.join(docs_touched)}")
        print(f"\n바뀐 코드가 인용한 절 — 빠진 게 없는지만 확인:\n{listing}")
        return 0

    print(f"::warning title=명세 동기화 확인::코드는 바뀌었는데 SPEC.md 가 그대로다. 확인할 절: {', '.join('§' + s for s in sections)}")
    print("\n바뀐 코드가 아래 절을 인용하고 있는데 명세는 그대로다.\n")
    print(listing)
    print(
        "\n명세가 바뀔 일이 아니라면(리팩터링·주석 등) 그냥 넘어가면 된다.\n"
        "동작이 명세와 달라졌다면 **이 PR 에서 함께** 고친다. 다음 PR 로 미루면 안 고쳐진다.\n"
        "(AGENTS.md §7)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
