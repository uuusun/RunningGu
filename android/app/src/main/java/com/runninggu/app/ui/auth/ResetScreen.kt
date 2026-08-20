package com.runninggu.app.ui.auth

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * A3 비밀번호 찾기 — 재설정 링크 방식. (SPEC §4.3 🔒확정 · AP-08)
 *
 * 앱은 메일 발송 요청까지만 한다. 새 비밀번호 설정은 링크가 여는 백엔드 웹 페이지 몫이다
 * (명세 §1-11·1-12). 발송 후 안내는 가입 여부와 무관하게 같다 — 계정 존재 비노출.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ResetViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("비밀번호 찾기", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
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
            Spacer(Modifier.height(16.dp))
            Text(
                text = "가입한 이메일을 알려 주세요",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "비밀번호를 다시 설정할 수 있는 링크를 메일로 보내드려요. 링크는 30분 동안 유효해요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            if (state.sent) {
                // 가입 여부와 무관하게 같은 문구 — 계정 존재를 노출하지 않는다 (SPEC §4.3-1).
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "가입된 이메일이면 재설정 링크를 보냈어요.\n메일함을 확인해 주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("로그인으로 돌아가기") }
            } else {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text("이메일") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    isError = state.errorMessage != null,
                    // 통신 실패는 "보냈어요" 로 덮지 않는다 (§4.3 은 서버 응답을 감추라는 뜻이다).
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
                        Text("재설정 메일 발송", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
