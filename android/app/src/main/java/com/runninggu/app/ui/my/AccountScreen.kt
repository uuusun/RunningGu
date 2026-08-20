package com.runninggu.app.ui.my

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runninggu.app.data.local.LoginProvider

/**
 * 계정 관리 — 마이 설정에서 여는 별도 화면. (SPEC §4.13 정보 수정 · D-22 · AP-13)
 *
 * 닉네임 · 마케팅 수신 동의 · 비밀번호 변경(EMAIL 가입자만) · 로그아웃 · 회원 탈퇴.
 * 로그인 방식 변경과 이메일 주소 변경은 MVP 범위 밖이다(#59 결정-22 개정).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingNickname by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var withdrawing by remember { mutableStateOf(false) }

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("계정 관리", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { innerPadding ->
        val profile = state.profile ?: return@Scaffold

        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("내 정보")
            SettingRow(
                label = "닉네임",
                value = profile.nickname,
                onClick = { editingNickname = true },
            )
            // 이메일은 읽기 전용이고, KAKAO 가입자가 미제공이면 행 자체를 숨긴다 (#59 확정).
            profile.email?.let { email ->
                SettingRow(label = "이메일", value = email, enabled = false)
            }
            SettingRow(
                label = "가입 방식",
                value = profile.loginProvider.label,
                enabled = false,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("알림")
            SwitchRow(
                label = "마케팅 정보 수신",
                description = "혜택·소식 메일을 받아요",
                checked = state.marketingAgreed,
                onToggle = viewModel::onToggleMarketing,
            )

            // 비밀번호 변경은 EMAIL 가입자에게만 (SPEC §4.13 · #59 loginProvider 기준).
            if (state.showsPasswordMenu) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("보안")
                SettingRow(
                    label = "비밀번호 변경",
                    value = "",
                    onClick = { changingPassword = true },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("계정")
            SettingRow(label = "로그아웃", value = "", onClick = viewModel::onLogout)
            SettingRow(
                label = "회원 탈퇴",
                value = "",
                destructive = true,
                onClick = { withdrawing = true },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editingNickname) {
        NicknameDialog(
            initial = state.profile?.nickname.orEmpty(),
            onDismiss = { editingNickname = false },
            onConfirm = { nickname ->
                viewModel.onNicknameChange(nickname)
                editingNickname = false
            },
        )
    }

    if (changingPassword) {
        PasswordDialog(
            onDismiss = { changingPassword = false },
            onConfirm = { current, new ->
                viewModel.onChangePassword(current, new)
                changingPassword = false
            },
        )
    }

    if (withdrawing) {
        WithdrawDialog(
            // 카카오 가입자는 비밀번호가 없어 SDK 재인증이다 — AP-02 연결 후 붙는다.
            requiresPassword = state.profile?.loginProvider == LoginProvider.EMAIL,
            onDismiss = { withdrawing = false },
            onConfirm = { password ->
                viewModel.onWithdraw(password)
                withdrawing = false
            },
        )
    }
}

// ── 조각 ────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                destructive -> MaterialTheme.colorScheme.error
                enabled -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun NicknameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("닉네임 변경", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("닉네임 (2~12자)") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (current: String, new: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("비밀번호 변경", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                PasswordField(value = current, onValueChange = { current = it }, label = "현재 비밀번호")
                Spacer(Modifier.height(8.dp))
                PasswordField(value = new, onValueChange = { new = it }, label = "새 비밀번호 (8자 이상, 영문+숫자)")
                Spacer(Modifier.height(8.dp))
                Text(
                    // D-28 — 변경 성공 시 다른 기기 세션은 전부 끊긴다.
                    text = "변경하면 다른 기기는 로그아웃돼요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(current, new) },
                enabled = current.isNotEmpty() && new.isNotEmpty(),
            ) { Text("변경") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun WithdrawDialog(
    requiresPassword: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("정말 탈퇴하시겠어요?", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "저장한 동선·코스와 찜한 대회가 모두 삭제되고 되돌릴 수 없어요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (requiresPassword) {
                    Spacer(Modifier.height(12.dp))
                    // 탈퇴 전 재인증 (SPEC §4.13 · D-23).
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "비밀번호 확인",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = !requiresPassword || password.isNotEmpty(),
            ) {
                Text("탈퇴", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
