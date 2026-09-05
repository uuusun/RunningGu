package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.KakaoAuthService;
import com.runninggu.server.auth.application.KakaoLoginResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/auth/kakao")
public class KakaoAuthController {

    private final KakaoAuthService kakaoAuthService;

    public KakaoAuthController(KakaoAuthService kakaoAuthService) {
        this.kakaoAuthService = kakaoAuthService;
    }

    @Operation(
            summary = "카카오 로그인 및 신규 가입 여부 확인",
            responses = @ApiResponse(
                    responseCode = "200",
                    content = @Content(
                            mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(oneOf = {
                        AuthSessionResponse.class,
                        KakaoPendingSignupResponse.class
                    }))))
    @PostMapping
    public KakaoLoginResponse login(@Valid @RequestBody KakaoLoginRequest request) {
        KakaoLoginResult result = kakaoAuthService.login(request.kakaoAccessToken());
        if (result.isNewUser()) {
            return KakaoPendingSignupResponse.from(result.profile());
        }
        return AuthSessionResponse.from(result.session());
    }

    @Operation(
            summary = "카카오 신규 회원가입",
            responses = @ApiResponse(
                    responseCode = "201",
                    content = @Content(
                            mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthSessionResponse.class))))
    @PostMapping("/signup")
    public ResponseEntity<AuthSessionResponse> signup(
            @Valid @RequestBody KakaoSignupRequest request) {
        AgreementsRequest agreements = request.agreements();
        AuthSessionResponse response = AuthSessionResponse.from(kakaoAuthService.signup(
                request.kakaoAccessToken(),
                request.nickname(),
                request.ageOver14(),
                agreements.tos(),
                agreements.privacy(),
                agreements.marketing()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
