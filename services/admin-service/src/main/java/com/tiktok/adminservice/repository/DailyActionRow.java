package com.tiktok.adminservice.repository;

import java.time.LocalDate;

/**
 * One day of the audit log, split by what the decision was.
 *
 * <p>One grouped query rather than four counting queries: the console asks for up to three
 * years of this at a time, and the split is only ever read together.
 */
public interface DailyActionRow {

    LocalDate getDay();

    long getTaken();

    long getUsersBanned();

    long getVideosTakenDown();

    long getCommentsRemoved();
}
