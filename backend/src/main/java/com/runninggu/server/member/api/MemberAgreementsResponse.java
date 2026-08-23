package com.runninggu.server.member.api;

import com.runninggu.server.member.application.MemberProfile;

public record MemberAgreementsResponse(
        boolean tos,
        boolean privacy,
        boolean marketing) {

    static MemberAgreementsResponse from(MemberProfile.Agreements agreements) {
        return new MemberAgreementsResponse(
                agreements.tos(),
                agreements.privacy(),
                agreements.marketing());
    }
}
