package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/password")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @Operation(summary = "비밀번호 재설정 링크 메일 발송")
    @PostMapping("/reset-request")
    public ResponseEntity<Void> request(
            @Valid @RequestBody PasswordResetEmailRequest request) {
        passwordResetService.request(request.email());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "새 비밀번호 설정")
    @PostMapping("/reset")
    public ResponseEntity<Void> reset(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
