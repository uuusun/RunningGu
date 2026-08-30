package com.runninggu.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.runninggu.app.data.local.AgreementDoc
import com.runninggu.app.data.local.AgreementMarkdown
import com.runninggu.app.data.local.AgreementTexts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * A2 회원가입 — 동의 → 정보 입력 → 이메일 인증 → 완료. (SPEC §4.2 · AP-08)
 *
 * 단계는 [SignupViewModel] 이 들고 있고, 뒤로가기는 단계 역행 후 첫 단계에서만 pop 된다.
 * 완료 단계의 [시작하기]는 자동 로그인으로 `home` 에 간다(명세 §1-5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignupScreen(
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    /** 카카오에서 넘어왔으면 토큰과 프로필이 들어 있다. `null` 이면 이메일 가입. (§1-7) */
    kakaoSignup: KakaoSignupHandoff? = null,
    viewModel: SignupViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ViewModel 이 한 번만 받는다 — 다시 불려도 사용자가 고쳐 둔 닉네임을 되돌리지 않는다
    LaunchedEffect(kakaoSignup) {
        kakaoSignup?.let {
            viewModel.startKakaoSignup(it.kakaoAccessToken, it.nickname, it.email)
        }
    }

    LaunchedEffect(state.completed) {
        if (state.completed) onCompleted()
    }

    val handleBack = {
        if (!viewModel.onStepBack()) onBack()
    }
    BackHandler { handleBack() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("회원가입", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (state.step != SignupStep.DONE) {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
        ) {
            when (state.step) {
                SignupStep.AGREE -> AgreeStep(state, viewModel)
                SignupStep.INFO -> InfoStep(state, viewModel)
                SignupStep.VERIFY -> VerifyStep(state, viewModel)
                SignupStep.DONE -> DoneStep(nickname = state.nickname, onStart = viewModel::onStart)
            }
        }
    }
}

// ── 1단계: 약관·개인정보 동의 (SPEC §4.2-1) ─────────────────────

@Composable
private fun AgreeStep(state: SignupUiState, viewModel: SignupViewModel) {
    StepTitle("약관에 동의해 주세요", "필수 항목에 동의해야 가입할 수 있어요.")

    // 어떤 문안을 열어 뒀는가. null 이면 닫혀 있다.
    var reading by remember { mutableStateOf<AgreementDoc?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            AgreeRow(
                label = "전체 동의",
                checked = state.allAgreed,
                onToggle = viewModel::onToggleAll,
                emphasized = true,
            )
            HorizontalDivider()
            AgreeRow(
                label = AgreementDoc.TOS.checkboxLabel,
                checked = state.tosAgreed,
                onToggle = viewModel::onToggleTos,
                onRead = { reading = AgreementDoc.TOS },
            )
            AgreeRow(
                label = AgreementDoc.PRIVACY.checkboxLabel,
                checked = state.privacyAgreed,
                onToggle = viewModel::onTogglePrivacy,
                onRead = { reading = AgreementDoc.PRIVACY },
            )
            AgreeRow(
                label = AgreementDoc.MARKETING.checkboxLabel,
                checked = state.marketingAgreed,
                onToggle = viewModel::onToggleMarketing,
                onRead = { reading = AgreementDoc.MARKETING },
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    CtaButton(text = "다음", enabled = state.canProceedAgree, onClick = viewModel::onAgreeNext)

    reading?.let { doc ->
        AgreementDialog(doc = doc, onDismiss = { reading = null })
    }
}

/**
 * 동의 한 줄. [onRead] 가 있으면 [보기] 를 붙인다.
 *
 * **체크박스와 [보기] 를 갈라 둔다.** 줄 전체를 눌러 문안이 열리면 체크하려다 열리고,
 * 반대로 줄 전체가 체크면 읽으려다 동의한 것이 된다. 동의는 되돌리기 쉬워야 하는 만큼
 * **누른 것과 일어난 일이 같아야** 한다.
 */
@Composable
private fun AgreeRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    emphasized: Boolean = false,
    onRead: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = label,
            style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else null,
            modifier = Modifier.weight(1f),
        )
        onRead?.let {
            TextButton(onClick = it) { Text("보기") }
        }
    }
}

/**
 * 약관 전문. (이슈 #111)
 *
 * **버전을 함께 보여준다.** 서버는 가입 시 활성 버전을 이력에 남기는데(§1-5), 사용자가
 * 무엇에 동의했는지 나중에 확인하려면 그때 본 글의 버전이 화면에도 있어야 한다.
 *
 * 마크다운 **기호만** 걷어 낸다([AgreementMarkdown]). 렌더러를 넣지 않는 이유는 새
 * 의존성이기도 하지만, 표가 접히거나 강조가 사라지는 식으로 **표시가 원문과 달라질 여지**를
 * 만들고 싶지 않아서다 — 동의 대상이 되는 글이다. 문장은 한 글자도 바뀌지 않는다.
 */
@Composable
private fun AgreementDialog(doc: AgreementDoc, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 파일 하나를 읽는 것이라 여기서 기억해 둔다. 스크롤할 때마다 다시 읽지 않는다.
    val text = remember(doc) {
        AgreementTexts.load(context, doc)?.let(AgreementMarkdown::toPlainText)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(doc.label, fontWeight = FontWeight.Bold)
                Text(
                    text = "버전 ${doc.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    // 못 읽어도 가입을 막지 않는다 — 내용만 못 보는 상태로 둔다.
                    text = text ?: "문안을 열지 못했어요. 앱을 다시 시작해 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

// ── 2단계: 정보 입력 (SPEC §4.2-2) ──────────────────────────────

@Composable
private fun NicknameField(state: SignupUiState, viewModel: SignupViewModel) {
    val nicknameFormatError = state.nickname.isNotEmpty() && !state.isNicknameValid
    OutlinedTextField(
        value = state.nickname,
        onValueChange = viewModel::onNicknameChange,
        label = { Text("닉네임 (2~12자)") },
        singleLine = true,
        isError = nicknameFormatError || state.nicknameCheck == DuplicateCheck.Duplicate,
        supportingText = inlineHint(nicknameFormatError, "2~12자로 지어 주세요")
            ?: duplicateHint(state.nicknameCheck, NICKNAME_HINTS),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusLost(viewModel::onNicknameFocusLost),
    )
}

/**
 * 가입 단계의 실패 문구. **두 갈래가 같은 것을 쓴다.** (#216 리뷰)
 *
 * 카카오 갈래는 [InfoStep] 중간에서 `return` 하므로 아래 공통 표시에 닿지 않는다. 각자
 * 그리게 두면 한쪽만 고쳐져 **오류가 조용히 사라지는** 자리가 생긴다 — 실제로 그랬다.
 */
@Composable
private fun SignupError(message: String?) {
    message ?: return
    Spacer(Modifier.height(8.dp))
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun InfoStep(state: SignupUiState, viewModel: SignupViewModel) {
    // 카카오는 이메일·비밀번호를 받지 않고 인증 단계도 없다(§1-8). 닉네임만 받는다
    if (state.isKakao) {
        StepTitle("닉네임을 정해 주세요", "카카오 계정으로 가입해요. 인증 절차는 없어요.")
        NicknameField(state, viewModel)
        // **여기서 return 하므로 아래 공통 오류 표시에 닿지 않는다** (#216 리뷰). 빼먹으면
        // `409 KAKAO_ACCOUNT_DUPLICATED` 처럼 **어디로 가야 하는지 알려 주는 문구**가
        // ViewModel 에만 있고 화면에는 안 뜬다 — 사용자는 버튼만 다시 누른다
        SignupError(state.errorMessage)
        Spacer(Modifier.height(24.dp))
        CtaButton(
            text = "가입 완료",
            enabled = state.canProceedInfo,
            isLoading = state.isSubmitting,
            onClick = viewModel::onInfoNext,
        )
        return
    }

    StepTitle("가입 정보를 입력해 주세요", "이메일로 인증 코드를 보내드려요.")

    val emailFormatError = state.email.isNotEmpty() && !state.isEmailValid
    OutlinedTextField(
        value = state.email,
        onValueChange = viewModel::onEmailChange,
        label = { Text("이메일") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        isError = emailFormatError || state.emailCheck == DuplicateCheck.Duplicate,
        // 형식 오류가 먼저다 — 형식이 틀리면 중복 확인을 아예 부르지 않는다.
        supportingText = inlineHint(emailFormatError, "이메일 형식을 확인해 주세요")
            ?: duplicateHint(state.emailCheck, EMAIL_HINTS),
        modifier = Modifier
            .fillMaxWidth()
            // 포커스가 빠질 때 한 번 확인한다 (D-30 · 이슈 #97)
            .onFocusLost(viewModel::onEmailFocusLost),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text("비밀번호 (8자 이상, 영문+숫자)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = state.password.isNotEmpty() && !state.isPasswordValid,
        supportingText = inlineHint(
            state.password.isNotEmpty() && !state.isPasswordValid,
            // 너무 길 때와 짧을 때는 할 일이 정반대라 문구를 가른다(§4.2-2 🔒).
            when (state.passwordIssue) {
                PasswordIssue.TOO_LONG -> "너무 길어요. 영문·숫자는 72자, 한글은 24자까지예요"
                else -> "8자 이상, 영문과 숫자를 함께 써 주세요"
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.passwordConfirm,
        onValueChange = viewModel::onPasswordConfirmChange,
        label = { Text("비밀번호 확인") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = state.passwordConfirm.isNotEmpty() && !state.isPasswordConfirmed,
        supportingText = inlineHint(state.passwordConfirm.isNotEmpty() && !state.isPasswordConfirmed, "비밀번호가 서로 달라요"),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    NicknameField(state, viewModel)

    SignupError(state.errorMessage)

    Spacer(Modifier.height(24.dp))
    CtaButton(
        text = "인증 메일 발송",
        enabled = state.canProceedInfo,
        isLoading = state.isSubmitting,
        onClick = viewModel::onInfoNext,
    )
}

// ── 3단계: 이메일 인증 (SPEC §4.2-3) ────────────────────────────

@Composable
private fun VerifyStep(state: SignupUiState, viewModel: SignupViewModel) {
    StepTitle(
        title = "메일로 보낸 코드를 입력해 주세요",
        subtitle = "${state.email} 로 6자리 코드를 보냈어요. 10분 안에 입력해 주세요.",
    )

    OutlinedTextField(
        value = state.code,
        onValueChange = viewModel::onCodeChange,
        label = { Text("인증 코드 6자리") },
        singleLine = true,
        // 만료·초과 상태에서는 입력을 막는다 — 같은 코드로 계속 시도해도 소용없다 (§1-4).
        enabled = !state.mustResend,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = state.errorMessage != null,
        supportingText = state.errorMessage?.let { message ->
            { Text(message, color = MaterialTheme.colorScheme.error) }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = viewModel::onResendCode,
        enabled = state.resendCooldownSec == 0 && !state.isSubmitting,
    ) {
        Text(
            text = when {
                state.resendCooldownSec > 0 -> "재발송 (${state.resendCooldownSec}초 후 가능)"
                // 재발송이 유일한 출구인 상태라 버튼을 강조한다 (NFR-10 🔒).
                state.mustResend -> "인증 메일 다시 받기"
                else -> "인증 메일 재발송"
            },
            fontWeight = if (state.mustResend) FontWeight.Bold else null,
        )
    }

    Spacer(Modifier.height(16.dp))
    CtaButton(
        text = "확인",
        enabled = state.canVerify,
        isLoading = state.isSubmitting,
        onClick = viewModel::onVerify,
    )
}

// ── 4단계: 완료 (SPEC §4.2-4) ───────────────────────────────────

@Composable
private fun DoneStep(nickname: String, onStart: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Text(
        text = "가입 완료!",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "${nickname.trim()}님, 이제 대회와 여행 동선을 한 번에 준비해 보세요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    CtaButton(text = "시작하기", enabled = true, onClick = onStart)
}

// ── 공통 조각 ───────────────────────────────────────────────────

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Spacer(Modifier.height(16.dp))
    Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun CtaButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    isLoading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
    Spacer(Modifier.height(24.dp))
}

/**
 * 중복 확인 결과 안내. (D-30 · 이슈 #97 합의 문구)
 *
 * **[DuplicateCheck.Error] 는 안내만 하고 재시도 버튼을 두지 않는다** — 포커스가 다시
 * 빠질 때 자동으로 다시 부른다. 필드마다 버튼이 붙으면 폼이 복잡해진다.
 */
@Composable
private fun duplicateHint(check: DuplicateCheck, hints: DuplicateHints): (@Composable () -> Unit)? =
    when (check) {
        DuplicateCheck.Unchecked -> null
        DuplicateCheck.Checking -> ({ Text(hints.checking) })
        DuplicateCheck.Available -> ({
            Text(hints.available, color = MaterialTheme.colorScheme.primary)
        })
        DuplicateCheck.Duplicate -> ({ Text(hints.duplicate) })
        // 진행을 막지 않으므로 오류 색을 쓰지 않는다 — 사용자가 할 일이 없다.
        DuplicateCheck.Error -> ({
            Text("확인하지 못했어요. 그대로 진행할 수 있어요.")
        })
    }

/** 필드별 중복 확인 문구. */
private data class DuplicateHints(val checking: String, val available: String, val duplicate: String)

private val EMAIL_HINTS = DuplicateHints(
    checking = "확인 중…",
    available = "사용할 수 있는 이메일이에요.",
    duplicate = "이미 가입된 이메일이에요.",
)

private val NICKNAME_HINTS = DuplicateHints(
    checking = "확인 중…",
    available = "사용할 수 있는 닉네임이에요.",
    duplicate = "이미 사용 중인 닉네임이에요.",
)

/** 포커스를 얻었다가 잃는 순간 한 번 부른다. 처음 그려질 때는 부르지 않는다. */
private fun Modifier.onFocusLost(action: () -> Unit): Modifier = composed {
    var hadFocus by remember { mutableStateOf(false) }
    onFocusChanged { focusState ->
        if (focusState.isFocused) {
            hadFocus = true
        } else if (hadFocus) {
            hadFocus = false
            action()
        }
    }
}

private fun inlineHint(show: Boolean, message: String): (@Composable () -> Unit)? =
    if (show) {
        { Text(message) }
    } else {
        null
    }
