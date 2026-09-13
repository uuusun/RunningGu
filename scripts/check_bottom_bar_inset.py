#!/usr/bin/env python3
"""`Scaffold(bottomBar = …)` 에 들어가는 하단 CTA 가 `BottomActionBar` 를 거치는지 확인한다.

같은 버그가 두 번 났다 — 3버튼 내비 기기에서 하단 CTA 아래 절반이 내비바에 가린다(#266).
#270 이 탭바 없는 화면의 하단 CTA 를 `BottomActionBar` 로 모아 `navigationBarsPadding` 을
먹였는데, #257 이 머지 충돌을 옛 `SaveBar` 로 풀면서 S7 만 되돌아갔다(#342). 방어선이
`SaveBar` 의 주석뿐이라 이 스크립트를 둔다(#346).

규칙 (SPEC §3-5 하단 고정 CTA):
  `bottomBar = { … }` 슬롯 안에서 호출되는 composable 은 **각각** 아래 중 하나여야 한다.
    - `BottomActionBar` 자체
    - 본문이 `BottomActionBar {` 를 호출하는 composable (`SaveBar` · `NextBar` …)
    - Material `NavigationBar` 를 그리는 composable — 탭바. inset 을 스스로 먹는다

  값(`shadowElevation = 8.dp`)이 아니라 **구조**를 본다. elevation 을 6.dp 로 바꾸거나
  `Box(contentAlignment = BottomCenter)` 로 그려도 잡혀야 해서다.

사용법:
    python scripts/check_bottom_bar_inset.py [ui 디렉터리]
    종료 코드 0 = 통과 · 1 = 위반 · 2 = 인자 오류
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

UI_DIR = Path("android/app/src/main/java/com/runninggu/app/ui")

# `bottomBar = {` 뒤의 블록을 여는 중괄호 위치. 공백·줄바꿈은 자유.
SLOT_OPEN = re.compile(r"bottomBar\s*=\s*\{")
# 블록 안의 composable 호출. 대문자로 시작하는 식별자 뒤에 `(` 또는 `{`.
CALL = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*[({]")
# composable 정의. `fun SaveBar(` 처럼 대문자로 시작하는 함수만.
FUN_DEF = re.compile(r"\bfun\s+([A-Z][A-Za-z0-9_]*)\s*\(")

# 호출되면 그 자체로 inset 을 처리하는 것.
SAFE_CALLS = {"BottomActionBar", "NavigationBar"}
# 제어문·수식자 등 composable 이 아닌데 대문자로 시작할 수 있는 것.
NOT_COMPOSABLE = {"Modifier", "Alignment", "Arrangement", "Color", "Icons"}


def block_after(text: str, open_brace: int) -> str:
    """`{` 위치에서 짝이 맞는 `}` 까지의 본문. 문자열 리터럴 안의 중괄호는 세지 않는다."""
    depth = 0
    i = open_brace
    in_str = False
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 1
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace + 1 : i]
        i += 1
    return text[open_brace + 1 :]


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def composable_bodies(sources: dict[str, str]) -> dict[str, tuple[str, str]]:
    """{이름: (파일, 본문)}. 같은 이름이 둘이면 마지막 것 — 이 코드베이스에는 없다."""
    bodies: dict[str, tuple[str, str]] = {}
    for path, text in sources.items():
        for m in FUN_DEF.finditer(text):
            # 시그니처 끝의 `{` 를 찾는다 — 반환형 생략 composable 은 `) {`.
            brace = text.find("{", m.end())
            if brace == -1:
                continue
            bodies[m.group(1)] = (path, block_after(text, brace))
    return bodies


def calls_in(block: str) -> list[str]:
    return [name for name in CALL.findall(block) if name not in NOT_COMPOSABLE]


def is_safe(name: str, bodies: dict[str, tuple[str, str]], seen: set[str] | None = None) -> bool:
    """이 composable 이 (직접 또는 한 단계 안에서) inset 을 처리하는가."""
    if name in SAFE_CALLS:
        return True
    if name not in bodies:
        return False
    seen = seen or set()
    if name in seen:
        return False
    seen.add(name)
    _, body = bodies[name]
    return any(is_safe(inner, bodies, seen) for inner in calls_in(body))


def check(ui_dir: Path) -> list[str]:
    sources = {
        str(p.relative_to(ui_dir)): strip_comments(p.read_text(encoding="utf-8"))
        for p in sorted(ui_dir.rglob("*.kt"))
    }
    bodies = composable_bodies(sources)
    violations: list[str] = []
    for path, text in sources.items():
        for m in SLOT_OPEN.finditer(text):
            slot = block_after(text, m.end() - 1)
            names = calls_in(slot)
            # 슬롯이 그리는 것 중 하나는 inset 을 처리해야 한다. `Surface(` 나 `Button(` 을
            # 슬롯에 직접 그리면 여기서 걸린다 — 라이브러리 composable 은 bodies 에 없어서
            # is_safe 가 False 다.
            if not any(is_safe(name, bodies) for name in names):
                violations.append(
                    f"{path}: bottomBar 슬롯이 BottomActionBar 를 거치지 않는다 (호출: {', '.join(names) or '없음'})"
                )
                continue
            # 이 저장소의 composable 을 슬롯에서 여러 개 부르면 각각이 안전해야 한다.
            for name in names:
                if name in bodies and not is_safe(name, bodies):
                    violations.append(
                        f"{path}: bottomBar 의 `{name}`({bodies[name][0]}) 이 BottomActionBar 를 거치지 않는다"
                    )
    return violations


def main(argv: list[str]) -> int:
    if len(argv) > 2:
        print(__doc__)
        return 2
    ui_dir = Path(argv[1]) if len(argv) == 2 else UI_DIR
    if not ui_dir.is_dir():
        print(f"ui 디렉터리가 없다: {ui_dir}")
        return 2
    violations = check(ui_dir)
    if violations:
        print("하단 CTA inset 규칙 위반 (#346 · SPEC §3-5):")
        for v in violations:
            print("  " + v)
        print("bottomBar 에 넣는 composable 은 BottomActionBar 를 거쳐야 한다 — ui/common/BottomActionBar.kt")
        return 1
    print("하단 CTA inset 규칙 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
