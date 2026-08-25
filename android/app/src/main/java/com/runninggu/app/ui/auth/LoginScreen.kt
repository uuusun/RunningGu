package com.runninggu.app.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.R
import kotlinx.coroutines.launch

/** 카카오 브랜드 버튼 색. 디자인 가이드 고정값이라 테마를 타지 않는다. */
private val KakaoYellow = Color(0xFFFEE500)
private val KakaoLabel = Color(0xFF191919)

/**
 * A1 로그인. (SPEC §4.1 · AP-08)
 *
 * 로고 · 이메일/비밀번호 · [로그인] · [카카오로 시작하기] · 회원가입/비밀번호 찾기 링크 ·
 * 게스트 [둘러보기]. 로그인 성공과 둘러보기는 `home` 으로 나간다(백스택 클리어는 NavHost 몫).
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onSignup: () -> Unit,
    onReset: () -> Unit,
    onBrowseAsGuest: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(96.dp))

            Image(
                painter = painterResource(R.drawable.app_icon),
                contentDescription = null,
                modifier = Modifier.size(88.dp),
            )
            Spacer(Modifier.height(16.dp))

            // 워드마크.
            Text(
                text = "런닝구",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "대회부터 여행 동선까지, 러너의 원정 준비",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(48.dp))

            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("이메일") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("비밀번호") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = state.errorMessage != null,
                supportingText = state.errorMessage?.let { message ->
                    { Text(message, color = MaterialTheme.colorScheme.error) }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = viewModel::onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("로그인", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                // TODO(AP-02): 카카오 콘솔 등록·네이티브 키 발급 후 카카오 로그인 SDK 를 연결한다
                //  (SPEC §4.1 · §7.4-10). 그 전에는 준비 중 안내만 띄운다.
                onClick = {
                    scope.launch { snackbarHostState.showSnackbar("카카오 로그인은 준비 중이에요") }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = KakaoYellow,
                    contentColor = KakaoLabel,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("카카오로 시작하기", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSignup) { Text("회원가입") }
                Text(
                    text = "·",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onReset) { Text("비밀번호 찾기") }
            }

            Spacer(Modifier.weight(1f))

            // 게스트 둘러보기 — 탐색·무상태 동선 생성은 로그인 없이 된다 (SPEC §4.1 🔒확정).
            TextButton(
                onClick = onBrowseAsGuest,
                modifier = Modifier.padding(bottom = 20.dp),
            ) {
                Text(
                    text = "로그인 없이 둘러보기",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
