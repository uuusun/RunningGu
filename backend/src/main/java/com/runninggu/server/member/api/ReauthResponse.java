package com.runninggu.server.member.api;

import com.runninggu.server.member.application.IssuedReauthToken;

public record ReauthResponse(
        String reauthToken,
        long expiresInSec) {

    public static ReauthResponse from(IssuedReauthToken issued) {
        return new ReauthResponse(issued.token(), issued.expiresInSec());
    }
}
