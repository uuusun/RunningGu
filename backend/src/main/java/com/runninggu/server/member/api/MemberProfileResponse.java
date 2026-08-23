package com.runninggu.server.member.api;

import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.member.application.MemberProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record MemberProfileResponse(
        long id,
        @Schema(nullable = true) String email,
        String nickname,
        LoginProvider loginProvider,
        MemberAgreementsResponse agreements,
        Instant createdAt) {

    static MemberProfileResponse from(MemberProfile profile) {
        return new MemberProfileResponse(
                profile.id(),
                profile.email(),
                profile.nickname(),
                profile.loginProvider(),
                MemberAgreementsResponse.from(profile.agreements()),
                profile.createdAt());
    }
}
