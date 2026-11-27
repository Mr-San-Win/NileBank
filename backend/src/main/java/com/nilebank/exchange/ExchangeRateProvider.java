package com.nilebank.exchange;

import com.nilebank.common.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public interface ExchangeRateProvider {
  // Shared with the exchange service; extend alongside wallet/schema currency support.
  List<String> SUPPORTED_CURRENCIES = SupportedCurrency.codes();
  List<String> LEGACY_CURRENCIES = List.of("THB", "USD", "EUR");

  private static ApiException invalidData() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "Exchange rate data is unavailable. Try again later.");
  }

  private static void positive(BigDecimal value) {
    if (value == null || value.signum() <= 0) throw invalidData();
  }

  private static Map<String, BigDecimal> validated(
      Map<String, BigDecimal> rates, boolean optional, List<String> declared) {
    if (rates == null) throw invalidData();
    if (optional && rates.isEmpty()) return Map.of();
    for (var entry : rates.entrySet()) {
      if (entry.getKey() == null || !declared.contains(entry.getKey())) throw invalidData();
      positive(entry.getValue());
    }
    if (!rates.keySet().containsAll(declared)) throw invalidData();
    return Map.copyOf(rates);
  }

  /** Exact numerator/denominator for target currency units per source currency unit. */
  record Conversion(BigDecimal numerator, BigDecimal denominator) {
    public Conversion {
      positive(numerator);
      positive(denominator);
    }
  }

  record Snapshot(
      String provider,
      LocalDate rateDate,
      Instant fetchedAt,
      Map<String, BigDecimal> thbPerUnit,
      Map<String, BigDecimal> unitsPerEuro,
      List<String> currencies) {
    /** Existing normalized snapshots, including fixed fixtures, retain their exact weights. */
    public Snapshot(
        String provider,
        LocalDate rateDate,
        Instant fetchedAt,
        Map<String, BigDecimal> thbPerUnit) {
      this(provider, rateDate, fetchedAt, thbPerUnit, Map.of(), LEGACY_CURRENCIES);
    }

    public Snapshot(
        String provider,
        LocalDate rateDate,
        Instant fetchedAt,
        Map<String, BigDecimal> thbPerUnit,
        Map<String, BigDecimal> unitsPerEuro) {
      this(provider, rateDate, fetchedAt, thbPerUnit, unitsPerEuro, LEGACY_CURRENCIES);
    }

    public Snapshot {
      if (currencies == null || currencies.isEmpty()) throw invalidData();
      for (String currency : currencies)
        if (currency == null || !SUPPORTED_CURRENCIES.contains(currency)) throw invalidData();
      if (!currencies.contains("THB")
          || new java.util.HashSet<>(currencies).size() != currencies.size()) throw invalidData();
      currencies = List.copyOf(currencies);
      thbPerUnit = validated(thbPerUnit, false, currencies);
      unitsPerEuro = validated(unitsPerEuro, true, currencies);
    }

    public Conversion conversion(String from, String to) {
      if (from == null || to == null || !currencies.contains(from) || !currencies.contains(to))
        throw invalidData();
      if (!unitsPerEuro.isEmpty()) {
        // Published EUR-base values are exact decimals; never normalize them before settlement.
        return new Conversion(unitsPerEuro.get(to), unitsPerEuro.get(from));
      }
      return new Conversion(thbPerUnit.get(from), thbPerUnit.get(to));
    }
  }

  Snapshot fetch();
}
