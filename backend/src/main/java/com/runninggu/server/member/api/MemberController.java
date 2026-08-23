package com.runninggu.server.member.api;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.member.application.MemberProfileService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/me")
public class MemberController {

    private final MemberProfileService memberProfileService;

    public MemberController(MemberProfileService memberProfileService) {
        this.memberProfileService = memberProfileService;
    }

    @Operation(summary = "내 프로필 조회")
    @GetMapping
    public MemberProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        return MemberProfileResponse.from(memberProfileService.getProfile(userId(jwt)));
    }

    @Operation(summary = "닉네임 변경")
    @PatchMapping
    public MemberProfileResponse updateNickname(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateNicknameRequest request) {
        return MemberProfileResponse.from(
                memberProfileService.updateNickname(userId(jwt), request.nickname()));
    }

    @Operation(summary = "마케팅 동의 변경")
    @PatchMapping("/agreements")
    public MemberProfileResponse updateAgreements(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateMarketingAgreementRequest request) {
        return MemberProfileResponse.from(memberProfileService.updateMarketingAgreement(
                userId(jwt), request.marketing()));
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
