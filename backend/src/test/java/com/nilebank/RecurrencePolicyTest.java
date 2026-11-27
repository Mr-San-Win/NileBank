package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.finance.RecurrencePolicy;
import com.nilebank.finance.RecurrencePolicy.Frequency;
import java.time.*;
import org.junit.jupiter.api.Test;

class RecurrencePolicyTest {
  @Test
  void monthlyAnchorSurvivesShortMonthsAndLeapYears() {
    var start = LocalDate.of(2026, 1, 31);
    var feb = RecurrencePolicy.next(start, start, Frequency.MONTHLY);
    assertThat(feb).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(RecurrencePolicy.next(start, feb, Frequency.MONTHLY))
        .isEqualTo(LocalDate.of(2026, 3, 31));
    var leap = LocalDate.of(2028, 1, 31);
    assertThat(RecurrencePolicy.next(leap, leap, Frequency.MONTHLY))
        .isEqualTo(LocalDate.of(2028, 2, 29));
  }

  @Test
  void dailyWeeklyAndResumeDatesRemainOnTheirSchedule() {
    var start = LocalDate.of(2026, 3, 1);
    assertThat(RecurrencePolicy.next(start, start, Frequency.DAILY)).isEqualTo(start.plusDays(1));
    assertThat(RecurrencePolicy.next(start, start, Frequency.WEEKLY)).isEqualTo(start.plusDays(7));
    assertThat(RecurrencePolicy.onOrAfter(start, start.plusDays(8), Frequency.WEEKLY))
        .isEqualTo(start.plusDays(14));
    assertThat(
            RecurrencePolicy.onOrAfter(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 15), Frequency.MONTHLY))
        .isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(RecurrencePolicy.onOrAfter(start, start.minusDays(1), Frequency.DAILY))
        .isEqualTo(start);
  }
}
