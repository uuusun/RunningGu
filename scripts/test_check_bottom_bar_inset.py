"""`check_bottom_bar_inset.py` 가 #257 식 회귀를 실제로 잡는지 고정한다. (#346)

    cd scripts && python -m unittest test_check_bottom_bar_inset -v
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import check_bottom_bar_inset as guard

COMMON = """
@Composable
fun BottomActionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Column(modifier.navigationBarsPadding().padding(20.dp), content = content)
    }
}
"""

# #270 이 만든 모양 — 공통 바를 거친다.
GOOD_SAVE_BAR = """
@Composable
fun ResultScreen() {
    Scaffold(
        bottomBar = {
            // 복원 화면에는 저장 CTA 가 없다 (#213)
            if (state.phase == Phase.CONTENT && !state.isSavedEditing) {
                SaveBar(save = state.save, canSave = state.canSave, onSave = viewModel::onSave)
            }
        },
    ) { }
}

@Composable
private fun SaveBar(save: SaveItineraryState, canSave: Boolean, onSave: () -> Unit) {
    BottomActionBar {
        Button(onClick = onSave, enabled = canSave) { Text("이 동선 저장하기") }
    }
}
"""

# #257 이 되돌린 모양 — Surface + Column 을 직접 그린다. 값은 공통 바와 똑같다.
REGRESSED_SAVE_BAR = GOOD_SAVE_BAR.replace(
    """    BottomActionBar {
        Button(onClick = onSave, enabled = canSave) { Text("이 동선 저장하기") }
    }""",
    """    Surface(shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Button(onClick = onSave, enabled = canSave) { Text("이 동선 저장하기") }
        }
    }""",
)

# 슬롯에 라이브러리 composable 을 바로 그린 것.
DIRECT_BUTTON = """
@Composable
fun StayScreen() {
    Scaffold(bottomBar = { Button(onClick = onNext) { Text("다음") } }) { }
}
"""

# 안전한 바 옆에 inset 없는 라이브러리 composable 을 같이 그린 것 (#350 리뷰). 하나가 안전하다고
# 슬롯 전체를 통과시키면 이게 샌다.
MIXED = """
@Composable
fun StayScreen() {
    Scaffold(bottomBar = {
        BottomActionBar { Button(onClick = onNext) { Text("다음") } }
        Button(onClick = onSkip) { Text("건너뛰기") }
    }) { }
}
"""

# 같은 혼합이지만 두 번째가 이 저장소의 composable 인 경우 — 파일까지 짚어야 한다.
MIXED_REPO = """
@Composable
fun StayScreen() {
    Scaffold(bottomBar = {
        NextBar(enabled = true, onClick = onNext)
        SkipBar(onClick = onSkip)
    }) { }
}

@Composable
internal fun NextBar(enabled: Boolean, onClick: () -> Unit) {
    BottomActionBar { Button(onClick = onClick, enabled = enabled) { Text("다음") } }
}

@Composable
private fun SkipBar(onClick: () -> Unit) {
    Surface(shadowElevation = 8.dp) { TextButton(onClick = onClick) { Text("건너뛰기") } }
}
"""

# if / else 로 갈라 그려도 가지마다 검사한다.
BRANCHED = """
@Composable
fun ResultScreen() {
    Scaffold(
        bottomBar = {
            if (state.isSavedEditing) {
                Button(onClick = onDone) { Text("완료") }
            } else {
                SaveBar(save = state.save, canSave = state.canSave, onSave = viewModel::onSave)
            }
        },
    ) { }
}

@Composable
private fun SaveBar(save: SaveItineraryState, canSave: Boolean, onSave: () -> Unit) {
    BottomActionBar {
        Button(onClick = onSave, enabled = canSave) { Text("이 동선 저장하기") }
    }
}
"""

# 래퍼 안에서 상태별로 갈리는 것 (#350 재리뷰). 안전한 가지 하나가 Button 가지를 가리면 안 된다.
BRANCHED_WRAPPER = """
@Composable
fun Screen() { Scaffold(bottomBar = { Wrapper(ok) }) { } }

@Composable
fun Wrapper(ok: Boolean) {
    if (ok) BottomActionBar { }
    else Button(onClick = {}) { }
}
"""

# 기본 람다 인자가 있는 composable (#350 재리뷰). `fun` 뒤 첫 `{` 를 본문으로 잡으면 기본값
# `{ BottomActionBar { } }` 를 본문으로 읽어, 실제 본문이 Button 인데도 통과한다.
DEFAULT_LAMBDA_ARG = """
@Composable fun Screen() { Scaffold(bottomBar = { Unsafe() }) { } }
@Composable fun Unsafe(
    extra: @Composable () -> Unit = { BottomActionBar { } },
) { Button(onClick = {}) { } }
"""

# 반대 방향 — 기본 람다는 Button 인데 실제 본문은 안전하다. 기본값을 본문으로 읽으면 이번엔 오탐이다.
DEFAULT_LAMBDA_ARG_SAFE = """
@Composable fun Screen() { Scaffold(bottomBar = { Safe() }) { } }
@Composable fun Safe(
    extra: @Composable () -> Unit = { Button(onClick = {}) { } },
) { BottomActionBar { } }
"""

# 형제 경로가 같은 안전한 래퍼를 거친다 (#350 재리뷰). `seen` 을 형제끼리 공유하면 두 번째 경로에서
# `Safe` 를 순환으로 오인해 멀쩡한 화면이 실패한다.
SHARED_SAFE_WRAPPER = """
@Composable
fun Screen() { Scaffold(bottomBar = { Outer() }) { } }

@Composable
fun Outer() { A(); B() }

@Composable
fun A() { Safe() }

@Composable
fun B() { Safe() }

@Composable
fun Safe() { BottomActionBar { } }
"""

# 같은 안전한 래퍼를 두 번 호출하는 것도 마찬가지다.
REPEATED_SAFE_WRAPPER = """
@Composable
fun Screen() { Scaffold(bottomBar = { Outer() }) { } }

@Composable
fun Outer() { Safe(); Safe() }

@Composable
fun Safe() { BottomActionBar { } }
"""

# 진짜 순환은 여전히 실패해야 한다 — 사본으로 넘겨도 같은 경로 안에서는 잡힌다.
RECURSIVE_WRAPPER = """
@Composable
fun Screen() { Scaffold(bottomBar = { Loop() }) { } }

@Composable
fun Loop() { Loop() }
"""

# 탭바 — Material NavigationBar 가 inset 을 스스로 먹는다.
TAB_BAR = """
@Composable
fun RunningGuApp() {
    Scaffold(
        bottomBar = {
            if (currentTab != null) {
                RunningGuBottomBar(currentTab = currentTab, onTabSelected = { })
            }
        },
    ) { }
}

@Composable
private fun RunningGuBottomBar(currentTab: Tab, onTabSelected: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { }
}
"""

# S4 · S5 처럼 한 단계 더 거친다: PrefsScreen → NextBar → BottomActionBar.
INDIRECT = """
@Composable
fun PrefsScreen() {
    Scaffold(bottomBar = { NextBar(enabled = true, onClick = onNext) }) { }
}

@Composable
internal fun NextBar(enabled: Boolean, onClick: () -> Unit) {
    BottomActionBar { Button(onClick = onClick, enabled = enabled) { Text("다음") } }
}
"""


def run_on(**files: str) -> list[str]:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "common").mkdir()
        (root / "common" / "BottomActionBar.kt").write_text(COMMON, encoding="utf-8")
        for name, text in files.items():
            (root / f"{name}.kt").write_text(text, encoding="utf-8")
        return guard.check(root)


class BottomBarInsetGuardTest(unittest.TestCase):
    def test_공통_바를_거치는_저장_바는_통과한다(self):
        self.assertEqual(run_on(ResultScreen=GOOD_SAVE_BAR), [])

    def test_257_식으로_Surface_를_직접_그리면_잡힌다(self):
        violations = run_on(ResultScreen=REGRESSED_SAVE_BAR)
        self.assertEqual(len(violations), 1)
        self.assertIn("SaveBar", violations[0])
        self.assertIn("ResultScreen.kt", violations[0])

    def test_슬롯에_라이브러리_composable_을_바로_그리면_잡힌다(self):
        violations = run_on(StayScreen=DIRECT_BUTTON)
        self.assertEqual(len(violations), 1)
        self.assertIn("StayScreen.kt", violations[0])
        self.assertIn("Button", violations[0])

    def test_안전한_바_옆에_Button_을_같이_그리면_그것만_잡힌다(self):
        violations = run_on(StayScreen=MIXED)
        self.assertEqual(len(violations), 1)
        self.assertIn("Button", violations[0])
        self.assertNotIn("BottomActionBar 를 거치지 않는다 (호출", violations[0])

    def test_안전한_바_옆의_저장소_composable_도_각각_검사한다(self):
        violations = run_on(StayScreen=MIXED_REPO)
        self.assertEqual(len(violations), 1)
        self.assertIn("SkipBar", violations[0])
        self.assertNotIn("NextBar", violations[0])

    def test_if_else_가지마다_검사한다(self):
        violations = run_on(ResultScreen=BRANCHED)
        self.assertEqual(len(violations), 1)
        self.assertIn("Button", violations[0])

    def test_후행_람다_안의_자식은_슬롯의_호출로_세지_않는다(self):
        # `BottomActionBar { Button { Text } }` 에서 Button · Text 는 바의 자식이다.
        self.assertEqual(guard.top_level_calls(' BottomActionBar { Button(onClick = a) { Text("x") } } '), ["BottomActionBar"])
        self.assertEqual(guard.top_level_calls(' if (x) { A() } else { B { } } C(1) { }'), ["A", "B", "C"])

    def test_래퍼_안에서_가지마다_갈리면_안전하지_않은_가지가_잡힌다(self):
        violations = run_on(Screen=BRANCHED_WRAPPER)
        self.assertEqual(len(violations), 1)
        self.assertIn("Wrapper", violations[0])

    def test_기본_람다_인자를_본문으로_오인하지_않는다(self):
        violations = run_on(Screen=DEFAULT_LAMBDA_ARG)
        self.assertEqual(len(violations), 1)
        self.assertIn("Unsafe", violations[0])
        # 반대로 기본값이 Button 이고 본문이 안전하면 통과해야 한다.
        self.assertEqual(run_on(Screen=DEFAULT_LAMBDA_ARG_SAFE), [])

    def test_형제_경로가_같은_안전한_래퍼를_거쳐도_통과한다(self):
        self.assertEqual(run_on(Screen=SHARED_SAFE_WRAPPER), [])
        self.assertEqual(run_on(Screen=REPEATED_SAFE_WRAPPER), [])

    def test_진짜_순환은_여전히_잡힌다(self):
        violations = run_on(Screen=RECURSIVE_WRAPPER)
        self.assertEqual(len(violations), 1)
        self.assertIn("Loop", violations[0])

    def test_탭바는_NavigationBar_로_통과한다(self):
        self.assertEqual(run_on(RunningGuApp=TAB_BAR), [])

    def test_한_단계_거친_NextBar_도_통과한다(self):
        self.assertEqual(run_on(PrefsScreen=INDIRECT), [])

    def test_주석_속_BottomActionBar_는_세지_않는다(self):
        # 주석에만 이름이 있고 실제로는 Surface 를 그린다 — #342 가 남긴 주석이 딱 이 모양이다.
        commented = REGRESSED_SAVE_BAR.replace(
            "private fun SaveBar",
            "// **[BottomActionBar] 를 쓴다** — 직접 Surface 를 그리지 않는다.\nprivate fun SaveBar",
        )
        self.assertEqual(len(run_on(ResultScreen=commented)), 1)

    def test_같은_입력이면_결과가_같다(self):
        # 결정성 (AGENTS 3장 scripts 규칙)
        self.assertEqual(run_on(A=REGRESSED_SAVE_BAR, B=DIRECT_BUTTON), run_on(A=REGRESSED_SAVE_BAR, B=DIRECT_BUTTON))


if __name__ == "__main__":
    unittest.main()
