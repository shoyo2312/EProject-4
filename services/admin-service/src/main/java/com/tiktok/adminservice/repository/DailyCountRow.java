package com.tiktok.adminservice.repository;

import java.time.LocalDate;

/** One day's worth of a count — shared shape for the reports-created and actions-taken series. */
public interface DailyCountRow {

    LocalDate getDay();

    long getCnt();
}
