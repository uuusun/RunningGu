package com.runninggu.server.member.api;

import jakarta.validation.constraints.NotNull;

public record UpdateMarketingAgreementRequest(
        @NotNull Boolean marketing) {}
