package com.tiktok.videoservice.dto.response;

import java.time.LocalDate;

/**
 * One day of the library, by the day each video was uploaded.
 *
 * <p>{@code uploads} is a true flow: the videos that arrived that day. The two after it are
 * cohort counts, not flows — of the videos uploaded that day, how many are <em>now</em> waiting
 * on a reviewer, and how many are <em>now</em> unwatchable. There is no timestamp on a status
 * change to build a real flow from, and both of those states are ones a video only sits in
 * briefly, so dating them by upload is close enough to answer "is the backlog growing" and
 * wrong for anything that needs the day a decision was made.
 *
 * @param notPlayable PROCESSING plus FAILED — either way nobody can watch it
 */
public record DailyVideoStatsResponse(
        LocalDate day,
        long uploads,
        long pendingReview,
        long notPlayable
) {
}
