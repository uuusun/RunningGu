package com.runninggu.server.savedcourse.infrastructure;

import com.runninggu.server.savedcourse.domain.SavedCourse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedCourseRepository extends JpaRepository<SavedCourse, Long> {

    @Query(
            value = """
                    SELECT new com.runninggu.server.savedcourse.infrastructure.SavedCourseSummaryRow(
                        course.id,
                        course.courseName,
                        course.distanceKm,
                        course.durationMin,
                        course.gainM,
                        course.difficulty,
                        course.dataSource,
                        course.region,
                        course.savedAt)
                    FROM SavedCourse course
                    WHERE course.user.id = :userId
                    ORDER BY course.savedAt DESC, course.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(course.id)
                    FROM SavedCourse course
                    WHERE course.user.id = :userId
                    """)
    Page<SavedCourseSummaryRow> findSummariesByUserId(
            @Param("userId") long userId,
            Pageable pageable);
}
