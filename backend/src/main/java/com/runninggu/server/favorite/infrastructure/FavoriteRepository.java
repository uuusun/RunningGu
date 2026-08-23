package com.runninggu.server.favorite.infrastructure;

import com.runninggu.server.favorite.domain.Favorite;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    @Modifying
    @Query(
            value = """
                    INSERT INTO favorite(user_id, contest_id, created_at)
                    VALUES (:userId, :contestId, :createdAt)
                    ON CONFLICT (user_id, contest_id) DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("userId") long userId,
            @Param("contestId") long contestId,
            @Param("createdAt") Instant createdAt);

    @Modifying
    @Query(
            value = """
                    DELETE FROM favorite
                    WHERE user_id = :userId AND contest_id = :contestId
                    """,
            nativeQuery = true)
    int deleteByUserAndContest(
            @Param("userId") long userId,
            @Param("contestId") long contestId);

    @EntityGraph(attributePaths = "contest")
    Page<Favorite> findByUser_Id(long userId, Pageable pageable);

    @Query("""
            SELECT favorite.contest.id
            FROM Favorite favorite
            WHERE favorite.user.id = :userId
              AND favorite.contest.id IN :contestIds
            """)
    List<Long> findContestIds(
            @Param("userId") long userId,
            @Param("contestIds") Collection<Long> contestIds);
}
