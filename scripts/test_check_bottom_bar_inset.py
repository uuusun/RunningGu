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
