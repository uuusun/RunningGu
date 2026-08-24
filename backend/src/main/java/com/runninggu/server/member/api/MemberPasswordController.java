package com.runninggu.server.member.api;

import com.runninggu.server.auth.api.TokenPairResponse;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.member.application.MemberPasswordService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/me/password")
public class MemberPasswordController {

    private final MemberPasswordService memberPasswordService;

    public MemberPasswordController(MemberPasswordService memberPasswordService) {
        this.memberPasswordService = memberPasswordService;
    }

    @Operation(summary = "이메일 계정 비밀번호 변경")
    @PutMapping
    public TokenPairResponse changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        return TokenPairResponse.from(memberPasswordService.changePassword(
                userId(jwt), request.currentPassword(), request.newPassword()));
    }

    private long userId(Jwt jwt) {
        if (jwt == null) {
            throw unauthorized();
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException exception) {
            throw unauthorized();
        }
    }

    private ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
    }
}
