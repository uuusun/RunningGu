package com.runninggu.server.auth.application;

public interface RefreshTokenHasher {
    String hash(String rawToken);
}
