package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.EmailAuthService;
import com.runninggu.server.auth.application.RefreshSessionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class EmailAuthController {

    private final EmailAuthService emailAuthService;
    private final RefreshSessionService refreshSessionService;

    public EmailAuthController(
            EmailAuthService emailAuthService,
            RefreshSessionService refreshSessionService) {
        this.emailAuthService = emailAuthService;
        this.refreshSessionService = refreshSessionService;
    }

    @Operation(summary = "이메일 회원가입")
    @PostMapping("/signup")
    public ResponseEntity<AuthSessionResponse> signup(
            @Valid @RequestBody SignupRequest request) {
        AgreementsRequest agreements = request.agreements();
        AuthSessionResponse response = AuthSessionResponse.from(emailAuthService.signup(
                request.email(),
                request.password(),
                request.nickname(),
                agreements.tos(),
                agreements.privacy(),
                agreements.marketing()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "이메일 로그인")
    @PostMapping("/login")
    public AuthSessionResponse login(@Valid @RequestBody LoginRequest request) {
        return AuthSessionResponse.from(
                emailAuthService.login(request.email(), request.password()));
    }

    @Operation(summary = "토큰 재발급")
    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenPairResponse.from(
                refreshSessionService.refresh(request.refreshToken()));
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        refreshSessionService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
