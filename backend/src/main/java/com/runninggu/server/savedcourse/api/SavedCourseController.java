package com.runninggu.server.savedcourse.api;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.savedcourse.application.SavedCourseService;
import com.runninggu.server.savedcourse.application.SavedCourseViews.Saved;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "저장 코스", description = "사용자별 러닝 코스 snapshot")
@RestController
@RequestMapping("/api/me/courses")
public class SavedCourseController {

    private final SavedCourseService service;

    public SavedCourseController(SavedCourseService service) {
        this.service = service;
    }

    @Operation(summary = "코스 저장", description = "geometry가 같으면 기존 id를 반환하는 멱등 연산")
    @PostMapping
    public ResponseEntity<SavedCourseSaveResponse> save(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SaveSavedCourseRequest request) {
        Saved saved = service.save(userId(jwt), request.toCommand());
        SavedCourseSaveResponse response = SavedCourseSaveResponse.from(saved);
        if (!saved.created()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity
                .created(URI.create("/api/me/courses/" + saved.id()))
                .body(response);
    }

    @Operation(summary = "저장 코스 목록")
    @GetMapping
    public SavedCourseListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return SavedCourseListResponse.from(service.list(userId(jwt), page, size));
    }

    @Operation(summary = "저장 코스 상세")
    @GetMapping("/{id}")
    public SavedCourseDetailResponse details(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id) {
        return SavedCourseDetailResponse.from(service.details(userId(jwt), id));
    }

    @Operation(summary = "저장 코스 삭제")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id) {
        service.delete(userId(jwt), id);
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
