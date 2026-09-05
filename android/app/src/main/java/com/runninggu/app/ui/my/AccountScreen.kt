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
import androidx.compose.ui.platform.LocalContext
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
    // 카카오 재인증이 SDK 를 부르는 데 필요하다 (§2-2)
    val context = LocalContext.current

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
                onClick = viewModel::onNicknameEditOpen,
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
                // **모를 때는 왜 모르는지 적는다**(#290 리뷰). 잠그기만 하면 꺼진 스위치로
                // 보여서, 서버가 ON 인 사용자가 "동의 안 했구나" 로 읽는다.
                description = state.marketingNotice.description ?: "혜택·소식 메일을 받아요",
                checked = state.marketingAgreed,
                // 서버가 답할 때까지 잠근다. 스위치는 세션 값을 그리므로 그때까지 안 움직인다.
                // **재로그인 직후에는 값 자체를 모른다**(#287) — 로그인 응답에 약관이 없어서
                // `GET /me` 가 돌아와야 안다. 그동안 열어 두면 OFF 로 보이는 스위치를 눌러
                // 이미 동의한 사용자가 철회하게 된다.
                enabled = !state.savingMarketing && state.marketingKnown,
                onToggle = viewModel::onToggleMarketing,
                // 못 읽었으면 다시 읽을 길을 준다 — 없으면 앱을 껐다 켜는 수밖에 없다
                onRetry = viewModel::refreshProfile
                    .takeIf { state.marketingNotice == MarketingNotice.FAILED },
            )

            // 비밀번호 변경은 EMAIL 가입자에게만 (SPEC §4.13 · #59 loginProvider 기준).
            if (state.showsPasswordMenu) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("보안")
                SettingRow(
                    label = "비밀번호 변경",
                    value = "",
                    onClick = viewModel::onPasswordEditOpen,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("계정")
            SettingRow(label = "로그아웃", value = "", onClick = viewModel::onLogout)
            SettingRow(
                label = "회원 탈퇴",
                value = "",
                destructive = true,
                onClick = viewModel::onWithdrawOpen,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // 여닫는 판단이 서버 응답에 달려 있어 ViewModel 이 들고 있다 (이슈 #164)
    state.nicknameEdit?.let { edit ->
        NicknameDialog(
            initial = state.profile?.nickname.orEmpty(),
            edit = edit,
            onDismiss = viewModel::onNicknameEditDismiss,
            onConfirm = viewModel::onNicknameChange,
        )
    }

    // 닉네임과 같다 — 성공해야 닫힌다. 틀린 현재 비밀번호를 스낵바로 알리면
    // 사용자가 다시 열어 두 칸을 처음부터 입력해야 한다 (이슈 #164 · §2-1)
    state.passwordEdit?.let { edit ->
        PasswordDialog(
            saving = edit.saving,
            error = edit.error,
            onDismiss = viewModel::onPasswordEditDismiss,
            onConfirm = viewModel::onChangePassword,
        )
    }

    // 비밀번호 변경과 같다 — 성공해야 닫힌다. `401 REAUTH_FAILED` 를 스낵바로 알리면
    // 되돌릴 수 없는 조작을 처음부터 다시 시작해야 한다 (§2-2)
    state.withdraw?.let { edit ->
        WithdrawDialog(
            // **가입 경로를 명시적으로 가른다.** `== EMAIL` 로 판정하면 프로필이
            // null 일 때도 false 라 카카오로 읽힌다 — EMAIL 계정이 비밀번호 칸 없이
            // 카카오 SDK 재인증을 시작한다(#238 리뷰). 되돌릴 수 없는 조작이라 더 그렇다.
            mode = withdrawMode(state.profile?.loginProvider),
            saving = edit.saving,
            error = edit.error,
            serverDone = edit.serverDone,
            onDismiss = viewModel::onWithdrawDismiss,
            onGiveUp = viewModel::onWithdrawGiveUp,
            onConfirm = viewModel::onWithdraw,
            onConfirmKakao = { viewModel.onWithdrawWithKakao(context) },
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
    enabled: Boolean = true,
    onToggle: () -> Unit,
    /** 값을 못 읽었을 때의 재조회. null 이면 안 그린다 (#290 리뷰). */
    onRetry: (() -> Unit)? = null,
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
        // **스위치 대신 [다시 시도] 를 둔다.** 못 읽은 값을 스위치로 그리면 그 모양 자체가
        // "꺼져 있다" 는 말이 된다 — 잠갔어도 마찬가지다(#290 리뷰).
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("다시 시도") }
        } else {
            Switch(checked = checked, enabled = enabled, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun NicknameDialog(
    initial: String,
    edit: NicknameEdit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        // 보내는 중에는 바깥을 눌러도 닫히지 않는다 — 결과를 받을 자리가 없어진다
        onDismissRequest = { if (!edit.saving) onDismiss() },
        title = { Text("닉네임 변경", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("닉네임 (2~12자)") },
                    singleLine = true,
                    enabled = !edit.saving,
                    isError = edit.error != null,
                )
                // 중복 닉네임은 여기서 고쳐야 넘어간다. 스낵바로 보내면 다이얼로그가
                // 닫힌 뒤라 처음부터 다시 입력해야 한다 (이슈 #164)
                edit.error?.let { error ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = !edit.saving) {
                Text(if (edit.saving) "저장 중…" else "저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !edit.saving) { Text("취소") }
        },
    )
}

@Composable
private fun PasswordDialog(
    saving: Boolean,
    error: String?,
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
                PasswordField(
                    value = current,
                    onValueChange = { current = it },
                    label = "현재 비밀번호",
                    enabled = !saving,
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = new,
                    onValueChange = { new = it },
                    label = "새 비밀번호 (8자 이상, 영문+숫자)",
                    enabled = !saving,
                )
                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                enabled = !saving && current.isNotEmpty() && new.isNotEmpty(),
            ) { Text(if (saving) "바꾸는 중…" else "변경") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") }
        },
    )
}

/**
 * 탈퇴 재인증을 **무엇으로 할지.** (§2-2 · #238 리뷰)
 *
 * `loginProvider` 를 그대로 쓰지 않고 한 겹 두는 이유는, **모르는 값과 없는 값을
 * 갈라 막아야** 하기 때문이다. `== EMAIL` 같은 판정은 null 을 조용히 "카카오" 로
 * 만들어 버린다.
 */
enum class WithdrawMode {
    /** EMAIL 가입 — 현재 비밀번호를 받는다. */
    PASSWORD,

    /** KAKAO 가입 — SDK 가 방금 발급한 토큰으로 확인한다. 받을 값이 없다. */
    KAKAO,

    /**
     * 가입 경로를 모른다. **막는다.**
     *
     * 프로필이 아직 안 왔거나 세션이 끊겨 사라진 경우다. 되돌릴 수 없는 조작이라
     * 짐작으로 진행하지 않는다 — 어느 쪽으로 틀려도 사용자가 의도하지 않은 재인증이
     * 시작된다.
     */
    BLOCKED,
}

/** [WithdrawMode] 판정. 화면 밖에서 고정할 수 있게 순수 함수로 둔다. */
fun withdrawMode(provider: LoginProvider?): WithdrawMode = when (provider) {
    LoginProvider.EMAIL -> WithdrawMode.PASSWORD
    LoginProvider.KAKAO -> WithdrawMode.KAKAO
    null -> WithdrawMode.BLOCKED
}

@Composable
private fun WithdrawDialog(
    mode: WithdrawMode,
    saving: Boolean,
    error: String?,
    serverDone: Boolean,
    onDismiss: () -> Unit,
    onGiveUp: () -> Unit,
    onConfirm: (String) -> Unit,
    onConfirmKakao: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        // 서버가 이미 지웠으면 **바깥 탭으로는** 닫히지 않는다 — 되돌릴 수 없는 조작 뒤라
        // 실수로 스치는 것과 그만두겠다는 것을 가른다. 나가려면 [나중에] 를 누른다 (#212 리뷰)
        onDismissRequest = { if (!serverDone) onDismiss() },
        title = {
            Text(
                text = if (serverDone) "기기 정리만 남았어요" else "정말 탈퇴하시겠어요?",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = if (serverDone) {
                        "계정은 이미 삭제됐어요. 이 기기에 남은 로그인 정보만 지우면 끝나요."
                    } else {
                        "저장한 동선·코스와 찜한 대회가 모두 삭제되고 되돌릴 수 없어요."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (mode == WithdrawMode.PASSWORD && !serverDone) {
                    Spacer(Modifier.height(12.dp))
                    // 탈퇴 전 재인증 (SPEC §4.13 · D-23 · §2-2).
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "비밀번호 확인",
                        enabled = !saving,
                    )
                } else if (mode == WithdrawMode.KAKAO && !serverDone) {
                    // 카카오는 **SDK 가 방금 발급한** 토큰으로 재인증한다(§2-2). 비밀번호를
                    // 다시 묻는 것과 같은 자리라, [탈퇴] 를 누르면 카카오로 한 번 더 확인한다
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "카카오로 한 번 더 확인해요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!serverDone) {
                    // 가입 경로를 모른다 — 짐작으로 재인증을 시작하지 않는다(#238 리뷰)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "계정 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (mode == WithdrawMode.KAKAO && !serverDone) onConfirmKakao() else onConfirm(password)
                },
                enabled = when {
                    saving -> false
                    // 비밀번호는 이미 확인됐다. 다시 묻지 않는다
                    serverDone -> true
                    // 가입 경로를 모르면 **누를 수 없다** (#238 리뷰)
                    mode == WithdrawMode.BLOCKED -> false
                    // 카카오는 여기서 받을 값이 없다 — 누르면 SDK 가 본인 확인을 한다
                    mode == WithdrawMode.KAKAO -> true
                    else -> password.isNotEmpty()
                },
            ) {
                Text(
                    text = when {
                        saving && serverDone -> "지우는 중…"
                        saving -> "탈퇴하는 중…"
                        serverDone -> "다시 시도"
                        else -> "탈퇴"
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            // 서버가 지운 뒤에도 나갈 길은 둔다 (#212 리뷰). 재시도가 계속 실패하는 것은
            // 보통 저장소 쓰기 실패라 같은 자리에서 또 눌러도 같은 결과다. 계정은 이미
            // 없으므로 [나중에] 는 취소가 아니라 **로그아웃**이다 — 남는 것은 죽은 토큰이다
            TextButton(
                onClick = if (serverDone) onGiveUp else onDismiss,
                enabled = !saving,
            ) {
                Text(if (serverDone) "나중에" else "취소")
            }
        },
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
