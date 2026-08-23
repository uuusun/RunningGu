package com.runninggu.server.itinerary.infrastructure;

import com.runninggu.server.itinerary.domain.Itinerary;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    Optional<Itinerary> findByUser_IdAndContest_IdAndStartDateAndEndDate(
            long userId,
            long contestId,
            LocalDate startDate,
            LocalDate endDate);

    @EntityGraph(attributePaths = "contest")
    Page<Itinerary> findByUser_Id(long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"contest", "days"})
    Optional<Itinerary> findWithContestAndDaysById(long id);
}
