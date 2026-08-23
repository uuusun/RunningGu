package com.runninggu.server.favorite.api;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.favorite.application.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "찜", description = "사용자별 대회 찜")
@RestController
@RequestMapping("/api/me/favorites")
public class FavoriteController {

    private final FavoriteService service;

    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    @Operation(summary = "찜한 대회 목록")
    @GetMapping
    public FavoriteListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return FavoriteListResponse.from(service.list(userId(jwt), page, size));
    }

    @Operation(summary = "대회 찜", description = "이미 찜한 대회도 성공하는 멱등 연산")
    @PutMapping("/{contestId}")
    public ResponseEntity<Void> add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long contestId) {
        service.add(userId(jwt), contestId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "대회 찜 해제", description = "찜하지 않은 대회도 성공하는 멱등 연산")
    @DeleteMapping("/{contestId}")
    public ResponseEntity<Void> remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long contestId) {
        service.remove(userId(jwt), contestId);
        return ResponseEntity.noContent().build();
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException exception) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
        }
    }
}
