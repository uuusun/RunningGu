package com.runninggu.server.member.api;

import jakarta.validation.constraints.NotBlank;

public record UpdateNicknameRequest(
        @NotBlank String nickname) {}
