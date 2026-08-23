package com.runninggu.server.itinerary.infrastructure;

import com.runninggu.server.itinerary.domain.ItineraryDay;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryDayRepository extends JpaRepository<ItineraryDay, Long> {

    @EntityGraph(attributePaths = {"itinerary", "itinerary.user", "blocks"})
    Optional<ItineraryDay> findWithItineraryAndBlocksById(long id);
}
