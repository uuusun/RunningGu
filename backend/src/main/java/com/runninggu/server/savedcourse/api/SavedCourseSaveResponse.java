package com.runninggu.server.savedcourse.api;

import com.runninggu.server.savedcourse.application.SavedCourseViews.Saved;

public record SavedCourseSaveResponse(long id, boolean created) {

    public static SavedCourseSaveResponse from(Saved saved) {
        return new SavedCourseSaveResponse(saved.id(), saved.created());
    }
}
