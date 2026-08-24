package com.runninggu.server.member.api;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.member.application.MemberDeletionService;
import com.runninggu.server.member.application.MemberReauthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/me")
public class MemberAccountController {

    private static final String REAUTH_HEADER = "X-Reauth-Token";

    private final MemberReauthService memberReauthService;
    private final MemberDeletionService memberDeletionService;

    public MemberAccountController(
            MemberReauthService memberReauthService,
            MemberDeletionService memberDeletionService) {
        this.memberReauthService = memberReauthService;
        this.memberDeletionService = memberDeletionService;
    }

    @Operation(summary = "회원 탈퇴 재인증")
    @PostMapping("/reauth")
    public ReauthResponse reauthenticate(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReauthRequest request) {
        return ReauthResponse.from(memberReauthService.reauthenticate(
                userId(jwt),
                request.provider(),
                request.password(),
                request.kakaoAccessToken()));
    }

    @Operation(summary = "회원 탈퇴")
    @DeleteMapping
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = REAUTH_HEADER, required = false) String reauthToken) {
        memberDeletionService.delete(userId(jwt), reauthToken);
        return ResponseEntity.noContent().build();
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
