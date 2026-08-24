package com.runninggu.server.itinerary.application;

import com.runninggu.server.auth.domain.AppUser;
import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.contest.domain.Contest;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.infrastructure.ContestRepository;
import com.runninggu.server.itinerary.application.ItineraryBlockCommands.Add;
import com.runninggu.server.itinerary.application.ItineraryBlockCommands.FieldUpdate;
import com.runninggu.server.itinerary.application.ItineraryBlockCommands.Patch;
import com.runninggu.server.itinerary.application.ItineraryViews.Block;
import com.runninggu.server.itinerary.application.ItineraryViews.CurrentContest;
import com.runninggu.server.itinerary.application.ItineraryViews.Day;
import com.runninggu.server.itinerary.application.ItineraryViews.Details;
import com.runninggu.server.itinerary.application.ItineraryViews.Hotel;
import com.runninggu.server.itinerary.application.ItineraryViews.PageResult;
import com.runninggu.server.itinerary.application.ItineraryViews.Recovery;
import com.runninggu.server.itinerary.application.ItineraryViews.Reordered;
import com.runninggu.server.itinerary.application.ItineraryViews.Saved;
import com.runninggu.server.itinerary.application.ItineraryViews.Summary;
import com.runninggu.server.itinerary.application.PersistItineraryCommand.BlockInput;
import com.runninggu.server.itinerary.application.PersistItineraryCommand.DayInput;
import com.runninggu.server.itinerary.domain.BlockCategory;
import com.runninggu.server.itinerary.domain.BlockType;
import com.runninggu.server.itinerary.domain.GeneratedRecovery;
import com.runninggu.server.itinerary.domain.Itinerary;
import com.runninggu.server.itinerary.domain.ItineraryBlock;
import com.runninggu.server.itinerary.domain.ItineraryBlockDraft;
import com.runninggu.server.itinerary.domain.ItineraryBlockSnapshot;
import com.runninggu.server.itinerary.domain.ItineraryDay;
import com.runninggu.server.itinerary.domain.ItineraryDaySnapshot;
import com.runninggu.server.itinerary.domain.ItinerarySnapshot;
import com.runninggu.server.itinerary.domain.RecoveryPolicy;
import com.runninggu.server.itinerary.infrastructure.ItineraryDayRepository;
import com.runninggu.server.itinerary.infrastructure.ItineraryRepository;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장 동선 snapshot과 USER 편집을 소유권 경계 안에서 처리한다. (SPEC §5.7·§6.3) */
@Service
@Transactional(readOnly = true)
public class ItineraryPersistenceService {

    private static final int MAX_TRAVEL_DAYS = 7;
    private static final int MAX_PAGE_SIZE = 50;
    private static final LocalTime DEFAULT_RACE_START_TIME = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_NEW_BLOCK_TIME = LocalTime.of(13, 0);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final BigDecimal MIN_LAT = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);
    private static final BigDecimal MIN_LNG = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LNG = BigDecimal.valueOf(180);

    private final ItineraryRepository itineraryRepository;
    private final ItineraryDayRepository dayRepository;
    private final ContestRepository contestRepository;
    private final AppUserRepository userRepository;
    private final Clock clock;

    public ItineraryPersistenceService(
            ItineraryRepository itineraryRepository,
            ItineraryDayRepository dayRepository,
            ContestRepository contestRepository,
            AppUserRepository userRepository,
            Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.dayRepository = dayRepository;
        this.contestRepository = contestRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public Saved save(long userId, PersistItineraryCommand command) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> error(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다."));
        Contest contest = contest(command.contestId());
        ItinerarySnapshot snapshot = snapshot(command, contest);
        Instant now = clock.instant();

        Itinerary itinerary = itineraryRepository
                .findByUser_IdAndContest_IdAndStartDateAndEndDate(
                        userId,
                        contest.getId(),
                        snapshot.startDate(),
                        snapshot.endDate())
                .orElse(null);
        boolean replaced = itinerary != null;
        if (itinerary == null) {
            itinerary = Itinerary.create(user, contest, snapshot, now);
        } else {
            itinerary.replace(snapshot, now);
        }
        itineraryRepository.saveAndFlush(itinerary);
        return new Saved(itinerary.getId(), replaced);
    }

    @Transactional
    public Saved replace(long userId, long itineraryId, PersistItineraryCommand command) {
        Itinerary itinerary = owned(itineraryId, userId);
        if (!itinerary.getContest().getId().equals(command.contestId())) {
            throw validation("기존 동선과 contestId가 같아야 합니다.");
        }
        ItinerarySnapshot snapshot = snapshot(command, itinerary.getContest());
        itinerary.replace(snapshot, clock.instant());
        itineraryRepository.flush();
        return new Saved(itinerary.getId(), true);
    }

    public PageResult list(long userId, int pageNumber, int size) {
        validatePage(pageNumber, size);
        PageRequest pageable = PageRequest.of(
                pageNumber,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Itinerary> page = itineraryRepository.findByUser_Id(userId, pageable);
        List<Summary> content = page.getContent().stream().map(this::summary).toList();
        return new PageResult(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.hasNext());
    }

    public Details details(long userId, long itineraryId) {
        return detailsView(owned(itineraryId, userId));
    }

    @Transactional
    public void delete(long userId, long itineraryId) {
        itineraryRepository.delete(owned(itineraryId, userId));
    }

    @Transactional
    public Block addBlock(long userId, long itineraryId, long dayId, Add command) {
        ItineraryDay day = ownedDay(userId, itineraryId, dayId);
        ItineraryBlock block = day.addUserBlock(toDraft(command));
        dayRepository.flush();
        return blockView(block);
    }

    @Transactional
    public Block patchBlock(
            long userId,
            long itineraryId,
            long dayId,
            long blockId,
            Patch command) {
        ItineraryDay day = ownedDay(userId, itineraryId, dayId);
        ItineraryBlock block = block(day, blockId);
        ensureMutable(block);
        block.update(patchedDraft(block, command));
        dayRepository.flush();
        return blockView(block);
    }

    @Transactional
    public void deleteBlock(
            long userId,
            long itineraryId,
            long dayId,
            long blockId) {
        ItineraryDay day = ownedDay(userId, itineraryId, dayId);
        ItineraryBlock block = block(day, blockId);
        ensureMutable(block);
        day.remove(block);
        dayRepository.flush();
    }

    @Transactional
    public Reordered reorder(
            long userId,
            long itineraryId,
            long dayId,
            List<Long> blockIds) {
        ItineraryDay day = ownedDay(userId, itineraryId, dayId);
        if (blockIds != null && day.getBlocks().stream()
                .filter(block -> block.getBlockType() == BlockType.RACE)
                .map(ItineraryBlock::getId)
                .anyMatch(blockIds::contains)) {
            throw error(
                    ErrorCode.SYSTEM_BLOCK_IMMUTABLE,
                    "대회 일정은 순서를 변경할 수 없습니다.");
        }
        switch (day.reorderUserBlocks(blockIds)) {
            case SYSTEM_BLOCK_IMMUTABLE -> throw error(
                    ErrorCode.SYSTEM_BLOCK_IMMUTABLE,
                    "대회 일정의 고정 위치를 넘어서 순서를 변경할 수 없습니다.");
            case BLOCK_SET_MISMATCH -> throw error(
                    ErrorCode.BLOCK_SET_MISMATCH,
                    "blockIds는 해당 일자의 USER 블록 전체 집합과 일치해야 합니다.");
            case REORDERED -> {
                // 변경 감지는 JPA dirty checking에 맡긴다.
            }
        }
        dayRepository.flush();
        return new Reordered(day.getId(), day.getBlocks().stream()
                .sorted(java.util.Comparator.comparingInt(ItineraryBlock::getOrderNo))
                .map(this::blockView)
                .toList());
    }

    private ItinerarySnapshot snapshot(PersistItineraryCommand command, Contest contest) {
        if (command == null) {
            throw validation("동선 저장 요청이 필요합니다.");
        }
        if (command.startDate() == null || command.endDate() == null) {
            throw validation("startDate와 endDate 값이 필요합니다.");
        }
        LocalDate startDate = command.startDate();
        LocalDate endDate = command.endDate();
        validatePeriod(startDate, endDate, contest.getContestDate());
        ContestEventType event = parseEvent(command.event());
        List<PoiCategory> themes = parseThemes(command.themes());
        validateHotel(command.hotel());

        List<LocalDate> expectedDates = startDate.datesUntil(endDate.plusDays(1)).toList();
        List<DayInput> inputs = command.days();
        if (inputs == null || inputs.size() != expectedDates.size()) {
            throw validation("days는 여행 기간의 모든 날짜를 한 번씩 포함해야 합니다.");
        }
        Set<Integer> indexes = new HashSet<>();
        Set<LocalDate> dates = new HashSet<>();
        for (DayInput input : inputs) {
            if (input == null || !indexes.add(input.dayIndex()) || !dates.add(input.date())) {
                throw validation("days의 dayIndex와 date는 중복될 수 없습니다.");
            }
        }

        List<Integer> offsets = expectedDates.stream()
                .map(date -> Math.toIntExact(ChronoUnit.DAYS.between(contest.getContestDate(), date)))
                .toList();
        boolean hasPlusDay = offsets.stream().anyMatch(offset -> offset > 0);
        List<ItineraryDaySnapshot> days = new ArrayList<>();
        for (int position = 0; position < expectedDates.size(); position++) {
            LocalDate date = expectedDates.get(position);
            int offset = offsets.get(position);
            DayInput input = inputs.stream()
                    .filter(candidate -> candidate.date().equals(date)
                            && candidate.dayIndex() == offset)
                    .findFirst()
                    .orElseThrow(() -> validation(
                            "dayIndex는 대회일 기준 상대 오프셋이고 date와 일치해야 합니다."));
            List<ItineraryBlockSnapshot> blocks = blocks(input, contest, event, offset);
            days.add(new ItineraryDaySnapshot(
                    offset,
                    date,
                    RecoveryPolicy.isRecoveryDay(event, offset, hasPlusDay),
                    normalizeNullable(input.note()),
                    blocks));
        }

        GeneratedRecovery recovery = RecoveryPolicy.recoveryFor(event, offsets);
        return new ItinerarySnapshot(
                durationTitle(expectedDates.size()),
                event,
                themes,
                startDate,
                endDate,
                command.hotel() == null ? null : command.hotel().name().strip(),
                command.hotel() == null ? null : command.hotel().lat(),
                command.hotel() == null ? null : command.hotel().lng(),
                contest.getRegion(),
                recovery == null ? null : recovery.label(),
                recovery == null ? null : recovery.note(),
                days);
    }

    private List<ItineraryBlockSnapshot> blocks(
            DayInput day,
            Contest contest,
            ContestEventType event,
            int offset) {
        List<ItineraryBlockSnapshot> blocks = new ArrayList<>();
        if (offset == 0) {
            blocks.add(raceBlock(contest, event));
        }
        List<BlockInput> requested = day.blocks() == null ? List.of() : day.blocks();
        for (BlockInput input : requested) {
            if (input == null) {
                throw validation("blocks에 null을 넣을 수 없습니다.");
            }
            BlockType type = parseBlockType(input.blockType());
            if (type == BlockType.RACE) {
                continue;
            }
            if (input.systemManaged()) {
                throw validation("USER 블록은 systemManaged=false여야 합니다.");
            }
            blocks.add(userBlock(input, blocks.size()));
        }
        return List.copyOf(blocks);
    }

    private ItineraryBlockSnapshot raceBlock(Contest contest, ContestEventType event) {
        return new ItineraryBlockSnapshot(
                contest,
                BlockType.RACE,
                0,
                effectiveStartTime(contest),
                "🏁 " + contest.getName() + " 스타트",
                BlockCategory.RACE,
                contest.getPlace(),
                normalizeNullable(contest.getRoadAddress()),
                contest.getLat(),
                contest.getLng(),
                eventLabel(event) + " 완주 · 결승 후 샤워");
    }

    private ItineraryBlockSnapshot userBlock(BlockInput input, int orderNo) {
        String title = required(input.title(), "block.title");
        BlockCategory category = parseUserCategory(input.category());
        validateCoordinates(input.lat(), input.lng());
        return new ItineraryBlockSnapshot(
                null,
                BlockType.USER,
                orderNo,
                parseTime(input.startTime(), null, "block.startTime"),
                title,
                category,
                normalizeNullable(input.placeName()),
                normalizeNullable(input.address()),
                input.lat(),
                input.lng(),
                normalizeNullable(input.description()));
    }

    private ItineraryBlockDraft toDraft(Add command) {
        if (command == null) {
            throw validation("블록 추가 요청이 필요합니다.");
        }
        validateCoordinates(command.lat(), command.lng());
        return new ItineraryBlockDraft(
                parseTime(command.startTime(), DEFAULT_NEW_BLOCK_TIME, "startTime"),
                required(command.title(), "title"),
                parseUserCategory(command.category()),
                normalizeNullable(command.placeName()),
                normalizeNullable(command.address()),
                command.lat(),
                command.lng(),
                normalizeNullable(command.description()));
    }

    private ItineraryBlockDraft patchedDraft(ItineraryBlock block, Patch patch) {
        if (patch == null) {
            throw validation("블록 수정 요청이 필요합니다.");
        }
        LocalTime startTime = value(patch.startTime(), block.getStartTime(),
                value -> parseTime(value, null, "startTime"));
        String title = value(patch.title(), block.getTitle(), value -> required(value, "title"));
        BlockCategory category = value(
                patch.category(), block.getCategory(), this::parseUserCategory);
        String placeName = nullableValue(patch.placeName(), block.getPlaceName());
        String address = nullableValue(patch.address(), block.getAddress());
        BigDecimal lat = rawValue(patch.lat(), block.getLat());
        BigDecimal lng = rawValue(patch.lng(), block.getLng());
        String description = nullableValue(patch.description(), block.getDescription());
        validateCoordinates(lat, lng);
        return new ItineraryBlockDraft(
                startTime,
                title,
                category,
                placeName,
                address,
                lat,
                lng,
                description);
    }

    private Summary summary(Itinerary itinerary) {
        Contest contest = itinerary.getContest();
        return new Summary(
                itinerary.getId(),
                itinerary.getTitle(),
                contest.getId(),
                contest.getName(),
                itinerary.getEvent().name(),
                itinerary.getRegionSnapshot(),
                recovery(itinerary),
                itinerary.getStartDate(),
                itinerary.getEndDate(),
                placeCount(itinerary),
                itinerary.getCreatedAt(),
                contest.isActive(),
                needsRegeneration(itinerary));
    }

    private Details detailsView(Itinerary itinerary) {
        Contest contest = itinerary.getContest();
        List<Day> days = itinerary.getDays().stream()
                .sorted(java.util.Comparator.comparing(ItineraryDay::getDate))
                .map(this::dayView)
                .toList();
        return new Details(
                itinerary.getId(),
                itinerary.getTitle(),
                contest.getId(),
                itinerary.getEvent().name(),
                itinerary.getThemes().stream().map(Enum::name).toList(),
                itinerary.getStartDate(),
                itinerary.getEndDate(),
                itinerary.getHotelName() == null
                        ? null
                        : new Hotel(
                                itinerary.getHotelName(),
                                itinerary.getHotelLat(),
                                itinerary.getHotelLng()),
                recovery(itinerary),
                itinerary.getRegionSnapshot(),
                days,
                needsRegeneration(itinerary),
                new CurrentContest(
                        contest.getName(),
                        contest.getRegion(),
                        contest.getPlace(),
                        contest.getContestDate(),
                        contest.getStartTime() == null
                                ? null
                                : contest.getStartTime().format(TIME_FORMAT),
                        contest.getLat(),
                        contest.getLng(),
                        contest.isActive()));
    }

    private Day dayView(ItineraryDay day) {
        return new Day(
                day.getId(),
                day.getDayIndex(),
                day.getDate(),
                RecoveryPolicy.dayLabel(day.getDayIndex()),
                day.isRecovery(),
                day.getNote(),
                day.getBlocks().stream()
                        .sorted(java.util.Comparator.comparingInt(ItineraryBlock::getOrderNo))
                        .map(this::blockView)
                        .toList());
    }

    private Block blockView(ItineraryBlock block) {
        return new Block(
                block.getId(),
                block.getOrderNo(),
                block.getStartTime().format(TIME_FORMAT),
                block.getTitle(),
                block.getCategory().name(),
                block.getPlaceName(),
                block.getAddress(),
                block.getLat(),
                block.getLng(),
                block.getDescription(),
                block.getBlockType().name(),
                block.isSystemManaged());
    }

    private Recovery recovery(Itinerary itinerary) {
        return itinerary.getRecoveryLabel() == null
                ? null
                : new Recovery(itinerary.getRecoveryLabel(), itinerary.getRecoveryNote());
    }

    private int placeCount(Itinerary itinerary) {
        return itinerary.getDays().stream()
                .mapToInt(day -> (int) day.getBlocks().stream()
                        .filter(block -> block.getPlaceName() != null)
                        .count())
                .sum();
    }

    private boolean needsRegeneration(Itinerary itinerary) {
        Contest contest = itinerary.getContest();
        ItineraryDay raceDay = itinerary.getDays().stream()
                .filter(day -> day.getBlocks().stream()
                        .anyMatch(block -> block.getBlockType() == BlockType.RACE))
                .findFirst()
                .orElse(null);
        if (raceDay == null) {
            return true;
        }
        ItineraryBlock race = raceDay.getBlocks().stream()
                .filter(block -> block.getBlockType() == BlockType.RACE)
                .findFirst()
                .orElseThrow();
        return !raceDay.getDate().equals(contest.getContestDate())
                || !race.getStartTime().equals(effectiveStartTime(contest))
                || !Objects.equals(race.getPlaceName(), contest.getPlace())
                || !Objects.equals(itinerary.getRegionSnapshot(), contest.getRegion())
                || !coordinateEquals(race.getLat(), contest.getLat())
                || !coordinateEquals(race.getLng(), contest.getLng());
    }

    private Itinerary owned(long itineraryId, long userId) {
        Itinerary itinerary = itineraryRepository.findWithContestAndDaysById(itineraryId)
                .orElseThrow(() -> error(
                        ErrorCode.ITINERARY_NOT_FOUND,
                        "동선 ID " + itineraryId + "를 찾을 수 없습니다."));
        if (!itinerary.getUser().getId().equals(userId)) {
            throw error(ErrorCode.FORBIDDEN, "다른 사용자의 동선에는 접근할 수 없습니다.");
        }
        return itinerary;
    }

    private ItineraryDay ownedDay(long userId, long itineraryId, long dayId) {
        ItineraryDay day = dayRepository.findWithItineraryAndBlocksById(dayId)
                .orElseThrow(() -> error(ErrorCode.DAY_NOT_FOUND, "일정을 찾을 수 없습니다."));
        if (!day.getItinerary().getUser().getId().equals(userId)) {
            throw error(ErrorCode.FORBIDDEN, "다른 사용자의 일정에는 접근할 수 없습니다.");
        }
        if (!day.getItinerary().getId().equals(itineraryId)) {
            throw error(ErrorCode.DAY_NOT_FOUND, "해당 동선의 일정을 찾을 수 없습니다.");
        }
        return day;
    }

    private ItineraryBlock block(ItineraryDay day, long blockId) {
        return day.getBlocks().stream()
                .filter(block -> block.getId().equals(blockId))
                .findFirst()
                .orElseThrow(() -> error(ErrorCode.BLOCK_NOT_FOUND, "블록을 찾을 수 없습니다."));
    }

    private void ensureMutable(ItineraryBlock block) {
        if (block.getBlockType() == BlockType.RACE || block.isSystemManaged()) {
            throw error(
                    ErrorCode.SYSTEM_BLOCK_IMMUTABLE,
                    "대회 일정은 사용자가 변경할 수 없습니다.");
        }
    }

    private Contest contest(long contestId) {
        return contestRepository.findById(contestId)
                .orElseThrow(() -> error(
                        ErrorCode.CONTEST_NOT_FOUND,
                        "대회 ID " + contestId + "를 찾을 수 없습니다."));
    }

    private void validatePeriod(LocalDate start, LocalDate end, LocalDate contestDate) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (end.isBefore(start)
                || days > MAX_TRAVEL_DAYS
                || contestDate.isBefore(start)
                || contestDate.isAfter(end)) {
            throw error(
                    ErrorCode.INVALID_TRAVEL_PERIOD,
                    "여행 기간은 역순일 수 없고 최대 7일이며 대회일을 포함해야 합니다.");
        }
    }

    private ContestEventType parseEvent(String raw) {
        try {
            return ContestEventType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw validation("event 값은 K5, K10, HALF, FULL 중 하나여야 합니다.");
        }
    }

    private List<PoiCategory> parseThemes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw validation("themes는 한 개 이상이어야 합니다.");
        }
        List<PoiCategory> themes = new ArrayList<>();
        for (String value : raw) {
            try {
                PoiCategory category = PoiCategory.valueOf(value);
                if (category == PoiCategory.LODGING) {
                    throw validation("LODGING은 여행 취향으로 선택할 수 없습니다.");
                }
                themes.add(category);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw validation("themes 값이 올바르지 않습니다.");
            }
        }
        return List.copyOf(themes);
    }

    private BlockType parseBlockType(String raw) {
        try {
            return BlockType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw validation("blockType 값은 USER 또는 RACE여야 합니다.");
        }
    }

    private BlockCategory parseUserCategory(String raw) {
        try {
            BlockCategory category = BlockCategory.valueOf(raw);
            if (category == BlockCategory.RACE) {
                throw validation("USER 블록의 category는 RACE일 수 없습니다.");
            }
            return category;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw validation("category 값이 올바르지 않습니다.");
        }
    }

    private LocalTime parseTime(String raw, LocalTime fallback, String field) {
        if (raw == null || raw.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw validation(field + " 값이 필요합니다.");
        }
        try {
            return LocalTime.parse(raw, TIME_FORMAT);
        } catch (DateTimeException exception) {
            throw validation(field + " 값은 HH:mm 형식이어야 합니다.");
        }
    }

    private void validateHotel(PersistItineraryCommand.HotelInput hotel) {
        if (hotel == null) {
            return;
        }
        required(hotel.name(), "hotel.name");
        if (hotel.lat() == null || hotel.lng() == null) {
            throw validation("hotel 좌표가 필요합니다.");
        }
        validateCoordinates(hotel.lat(), hotel.lng());
    }

    private void validateCoordinates(BigDecimal lat, BigDecimal lng) {
        if (lat != null && (lat.compareTo(MIN_LAT) < 0 || lat.compareTo(MAX_LAT) > 0)) {
            throw validation("lat 값은 -90 이상 90 이하여야 합니다.");
        }
        if (lng != null && (lng.compareTo(MIN_LNG) < 0 || lng.compareTo(MAX_LNG) > 0)) {
            throw validation("lng 값은 -180 이상 180 이하여야 합니다.");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("page는 0 이상, size는 1 이상 50 이하여야 합니다.");
        }
    }

    private String required(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw validation(field + " 값이 필요합니다.");
        }
        return raw.strip();
    }

    private String durationTitle(int dayCount) {
        return dayCount == 1 ? "당일치기" : (dayCount - 1) + "박 " + dayCount + "일";
    }

    private LocalTime effectiveStartTime(Contest contest) {
        return contest.getStartTime() == null ? DEFAULT_RACE_START_TIME : contest.getStartTime();
    }

    private String eventLabel(ContestEventType event) {
        return switch (event) {
            case K5 -> "5K";
            case K10 -> "10K";
            case HALF -> "하프";
            case FULL -> "풀";
        };
    }

    private boolean coordinateEquals(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private <T, R> R value(FieldUpdate<T> update, R current, java.util.function.Function<T, R> parser) {
        return update == null || !update.present() ? current : parser.apply(update.value());
    }

    private <T> T rawValue(FieldUpdate<T> update, T current) {
        return update == null || !update.present() ? current : update.value();
    }

    private String nullableValue(FieldUpdate<String> update, String current) {
        return update == null || !update.present() ? current : normalizeNullable(update.value());
    }

    private ApiException validation(String detail) {
        return error(ErrorCode.VALIDATION_FAILED, detail);
    }

    private ApiException error(ErrorCode code, String detail) {
        return new ApiException(code, detail);
    }
}
