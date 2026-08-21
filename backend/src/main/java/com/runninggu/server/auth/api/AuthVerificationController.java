package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.DuplicateCheckService;
import com.runninggu.server.auth.application.EmailVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthVerificationController {

    private final DuplicateCheckService duplicateCheckService;
    private final EmailVerificationService emailVerificationService;

    public AuthVerificationController(
            DuplicateCheckService duplicateCheckService,
            EmailVerificationService emailVerificationService) {
        this.duplicateCheckService = duplicateCheckService;
        this.emailVerificationService = emailVerificationService;
    }

    @Operation(summary = "이메일 중복 확인")
    @GetMapping("/email/exists")
    public ExistsResponse emailExists(
            @RequestParam String email,
            HttpServletRequest request) {
        return new ExistsResponse(
                duplicateCheckService.emailExists(request.getRemoteAddr(), email));
    }

    @Operation(summary = "닉네임 중복 확인")
    @GetMapping("/nickname/exists")
    public ExistsResponse nicknameExists(
            @RequestParam String nickname,
            HttpServletRequest request) {
        return new ExistsResponse(
                duplicateCheckService.nicknameExists(request.getRemoteAddr(), nickname));
    }

    @Operation(summary = "가입 인증 코드 발송")
    @PostMapping("/email/send-code")
    public ResponseEntity<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
        emailVerificationService.sendSignupCode(request.email());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "가입 인증 코드 검증")
    @PostMapping("/email/verify")
    public VerifyCodeResponse verifyCode(@Valid @RequestBody VerifyCodeRequest request) {
        return new VerifyCodeResponse(
                emailVerificationService.verifySignupCode(request.email(), request.code()));
    }
}
