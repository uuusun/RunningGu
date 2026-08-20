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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    viewModel: SignupViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            AgreeRow("(필수) 이용약관 동의", state.tosAgreed, viewModel::onToggleTos)
            AgreeRow("(필수) 개인정보 수집·이용 동의", state.privacyAgreed, viewModel::onTogglePrivacy)
            AgreeRow("(선택) 마케팅 정보 수신 동의", state.marketingAgreed, viewModel::onToggleMarketing)
        }
    }

    Spacer(Modifier.height(24.dp))
    CtaButton(text = "다음", enabled = state.canProceedAgree, onClick = viewModel::onAgreeNext)
}

@Composable
private fun AgreeRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    emphasized: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = label,
            style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else null,
        )
    }
}

// ── 2단계: 정보 입력 (SPEC §4.2-2) ──────────────────────────────

@Composable
private fun InfoStep(state: SignupUiState, viewModel: SignupViewModel) {
    StepTitle("가입 정보를 입력해 주세요", "이메일로 인증 코드를 보내드려요.")

    OutlinedTextField(
        value = state.email,
        onValueChange = viewModel::onEmailChange,
        label = { Text("이메일") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        isError = state.email.isNotEmpty() && !state.isEmailValid,
        supportingText = inlineHint(state.email.isNotEmpty() && !state.isEmailValid, "이메일 형식을 확인해 주세요"),
        modifier = Modifier.fillMaxWidth(),
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
        supportingText = inlineHint(state.password.isNotEmpty() && !state.isPasswordValid, "8자 이상, 영문과 숫자를 함께 써 주세요"),
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
    OutlinedTextField(
        value = state.nickname,
        onValueChange = viewModel::onNicknameChange,
        label = { Text("닉네임 (2~12자)") },
        singleLine = true,
        isError = state.nickname.isNotEmpty() && !state.isNicknameValid,
        supportingText = inlineHint(state.nickname.isNotEmpty() && !state.isNicknameValid, "2~12자로 지어 주세요"),
        modifier = Modifier.fillMaxWidth(),
    )

    state.errorMessage?.let { message ->
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

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
            if (state.resendCooldownSec > 0) {
                "재발송 (${state.resendCooldownSec}초 후 가능)"
            } else {
                "인증 메일 재발송"
            },
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

private fun inlineHint(show: Boolean, message: String): (@Composable () -> Unit)? =
    if (show) {
        { Text(message) }
    } else {
        null
    }
