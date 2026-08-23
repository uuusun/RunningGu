package com.runninggu.server.itinerary.api;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.itinerary.application.ItineraryPersistenceService;
import com.runninggu.server.itinerary.application.ItineraryViews.Saved;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "동선", description = "대회 전후 여행 동선 생성·저장·편집")
@RestController
@RequestMapping("/api/itineraries")
public class ItineraryPersistenceController {

    private final ItineraryPersistenceService service;

    public ItineraryPersistenceController(ItineraryPersistenceService service) {
        this.service = service;
    }

    @Operation(summary = "동선 저장", description = "편집된 생성 결과를 snapshot으로 저장하거나 같은 여행을 교체")
    @PostMapping
    public ResponseEntity<ItinerarySaveResponse> save(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SaveItineraryRequest request) {
        Saved saved = service.save(userId(jwt), request.toCommand());
        ItinerarySaveResponse response = ItinerarySaveResponse.from(saved);
        if (saved.replaced()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity
                .created(URI.create("/api/itineraries/" + saved.id()))
                .body(response);
    }

    @Operation(summary = "재생성 동선으로 교체", description = "기존 id를 유지하며 트리와 snapshot을 원자적으로 교체")
    @PutMapping("/{id}")
    public ItinerarySaveResponse replace(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @Valid @RequestBody SaveItineraryRequest request) {
        return ItinerarySaveResponse.from(service.replace(userId(jwt), id, request.toCommand()));
    }

    @Operation(summary = "내 동선 목록")
    @GetMapping
    public ItineraryListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ItineraryListResponse.from(service.list(userId(jwt), page, size));
    }

    @Operation(summary = "저장 동선 상세")
    @GetMapping("/{id}")
    public ItineraryDetailResponse details(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id) {
        return ItineraryDetailResponse.from(service.details(userId(jwt), id));
    }

    @Operation(summary = "저장 동선 삭제")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id) {
        service.delete(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "USER 블록 추가", description = "해당 일자의 마지막에 추가")
    @PostMapping("/{id}/days/{dayId}/blocks")
    public ResponseEntity<ItineraryBlockCreatedResponse> addBlock(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @PathVariable long dayId,
            @Valid @RequestBody AddItineraryBlockRequest request) {
        ItineraryBlockCreatedResponse response = ItineraryBlockCreatedResponse.from(
                service.addBlock(userId(jwt), id, dayId, request.toCommand()));
        return ResponseEntity.created(URI.create(
                        "/api/itineraries/" + id + "/days/" + dayId + "/blocks/" + response.blockId()))
                .body(response);
    }

    @Operation(summary = "USER 블록 수정", description = "보낸 필드만 반영하며 명시적 null은 장소 값을 제거")
    @PatchMapping("/{id}/days/{dayId}/blocks/{blockId}")
    public ItineraryBlockResponse patchBlock(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @PathVariable long dayId,
            @PathVariable long blockId,
            @RequestBody PatchItineraryBlockRequest request) {
        return ItineraryBlockResponse.from(service.patchBlock(
                userId(jwt), id, dayId, blockId, request.toCommand()));
    }

    @Operation(summary = "USER 블록 삭제")
    @DeleteMapping("/{id}/days/{dayId}/blocks/{blockId}")
    public ResponseEntity<Void> deleteBlock(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @PathVariable long dayId,
            @PathVariable long blockId) {
        service.deleteBlock(userId(jwt), id, dayId, blockId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "USER 블록 순서 변경", description = "RACE 위치를 유지하고 USER 전체 집합을 재정렬")
    @PutMapping("/{id}/days/{dayId}/blocks/order")
    public ItineraryReorderResponse reorder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @PathVariable long dayId,
            @Valid @RequestBody ReorderItineraryBlocksRequest request) {
        return ItineraryReorderResponse.from(
                service.reorder(userId(jwt), id, dayId, request.blockIds()));
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException exception) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
        }
    }
}
