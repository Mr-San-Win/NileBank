package com.nilebank.finance;

import java.time.*;
import java.time.temporal.ChronoUnit;

public final class RecurrencePolicy {
  public enum Frequency {
    DAILY,
    WEEKLY,
    MONTHLY
  }

  private RecurrencePolicy() {}

  public static LocalDate next(LocalDate anchor, LocalDate current, Frequency frequency) {
    return switch (frequency) {
      case DAILY -> current.plusDays(1);
      case WEEKLY -> current.plusWeeks(1);
      case MONTHLY -> inMonth(anchor, YearMonth.from(current).plusMonths(1));
    };
  }

  private static LocalDate inMonth(LocalDate anchor, YearMonth month) {
    return month.atDay(Math.min(anchor.getDayOfMonth(), month.lengthOfMonth()));
  }

  public static LocalDate onOrAfter(LocalDate anchor, LocalDate day, Frequency frequency) {
    if (!day.isAfter(anchor)) return anchor;
    return switch (frequency) {
      case DAILY -> day;
      case WEEKLY -> anchor.plusWeeks((ChronoUnit.DAYS.between(anchor, day) + 6) / 7);
      case MONTHLY -> {
        var candidate = inMonth(anchor, YearMonth.from(day));
        yield candidate.isBefore(day)
            ? inMonth(anchor, YearMonth.from(day).plusMonths(1))
            : candidate;
      }
    };
  }
}
