package com.runninggu.server.member.application;

public record IssuedReauthToken(
        String token,
        long expiresInSec) {}
