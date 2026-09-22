package com.tiktok.adminservice.repository;

import java.time.LocalDate;

/** One day's report closures, by the outcome they were closed with. */
public interface DailyResolutionRow {

    LocalDate getDay();

    long getResolved();

    long getDismissed();
}
