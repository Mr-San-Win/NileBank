package com.nilebank.exchange;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;

public interface ExchangeRateProvider {
  record Snapshot(
      String provider, LocalDate rateDate, Instant fetchedAt, Map<String, BigDecimal> thbPerUnit) {
    public Snapshot {
      thbPerUnit = Map.copyOf(thbPerUnit);
    }
  }

  Snapshot fetch();
}
