package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.common.ApiException;
import com.nilebank.exchange.ExchangeRateProvider;
import com.nilebank.exchange.ExchangeRateProvider.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ExchangeRateProviderTest {
  Map<String, BigDecimal> weights() {
    return new HashMap<>(
        Map.of(
            "THB",
            BigDecimal.ONE,
            "USD",
            new BigDecimal("35"),
            "EUR",
            new BigDecimal("38"),
            "MMK",
            new BigDecimal("0.02"),
            "SGD",
            new BigDecimal("25"),
            "JPY",
            new BigDecimal("0.25"),
            "CNY",
            new BigDecimal("5")));
  }

  Snapshot snapshot(Map<String, BigDecimal> normalized, Map<String, BigDecimal> raw) {
    return new Snapshot(
        "fixture",
        LocalDate.of(2026, 11, 20),
        Instant.EPOCH,
        normalized,
        raw,
        ExchangeRateProvider.SUPPORTED_CURRENCIES);
  }

  void invalid(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.getMessage())
                  .isEqualTo("Exchange rate data is unavailable. Try again later.");
            });
  }

  @Test
  void rejectsNullAndNonpositiveConversionOperands() {
    for (BigDecimal bad : Arrays.asList(null, BigDecimal.ZERO, BigDecimal.ONE.negate())) {
      invalid(() -> new Conversion(bad, BigDecimal.ONE));
      invalid(() -> new Conversion(BigDecimal.ONE, bad));
    }
  }

  @Test
  void preservesExactPositiveConversionOperands() {
    var n = new BigDecimal("0.000000000000000001");
    var d = new BigDecimal("1.234567890123456789");
    var conversion = new Conversion(n, d);
    assertThat(conversion.numerator()).isEqualTo(n);
    assertThat(conversion.denominator()).isEqualTo(d);
  }

  @Test
  void rejectsNullSnapshotMaps() {
    invalid(() -> snapshot(null, Map.of()));
    invalid(() -> snapshot(weights(), null));
    invalid(() -> new Snapshot("fixture", LocalDate.now(), Instant.now(), null));
  }

  @Test
  void rejectsMissingRatesInEitherRepresentation() {
    invalid(() -> snapshot(Map.of(), Map.of()));
    for (String currency : ExchangeRateProvider.SUPPORTED_CURRENCIES) {
      var incomplete = weights();
      incomplete.remove(currency);
      invalid(() -> snapshot(incomplete, Map.of()));
      invalid(() -> snapshot(weights(), incomplete));
    }
  }

  @Test
  void rejectsNullAndUnsupportedKeysInEitherRepresentation() {
    for (String key : Arrays.asList(null, "ZZZ")) {
      var invalid = weights();
      invalid.put(key, BigDecimal.ONE);
      invalid(() -> snapshot(invalid, Map.of()));
      invalid(() -> snapshot(weights(), invalid));
    }
  }

  @Test
  void rejectsNullZeroAndNegativeWeightsForEverySupportedCurrency() {
    for (String currency : ExchangeRateProvider.SUPPORTED_CURRENCIES) {
      for (BigDecimal bad : Arrays.asList(null, BigDecimal.ZERO, BigDecimal.ONE.negate())) {
        var invalid = weights();
        invalid.put(currency, bad);
        invalid(() -> snapshot(invalid, Map.of()));
        invalid(() -> snapshot(weights(), invalid));
      }
    }
  }

  @Test
  void defensivelyCopiesBothMapsAndAllowsNormalizedOnlySnapshots() {
    var normalized = weights();
    var raw = weights();
    var result = snapshot(normalized, raw);
    normalized.clear();
    raw.clear();
    assertThat(result.thbPerUnit()).hasSize(ExchangeRateProvider.SUPPORTED_CURRENCIES.size());
    assertThat(result.unitsPerEuro()).hasSize(ExchangeRateProvider.SUPPORTED_CURRENCIES.size());
    assertThatThrownBy(() -> result.thbPerUnit().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.unitsPerEuro().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(snapshot(weights(), Map.of()).conversion("THB", "USD").denominator())
        .isEqualByComparingTo("35");
  }

  @Test
  void rejectsUnsupportedConversionRequestsAndChecksCurrencyScales() {
    var rates = snapshot(weights(), Map.of());
    invalid(() -> rates.conversion(null, "THB"));
    invalid(() -> rates.conversion("THB", "ZZZ"));
    for (String currency : ExchangeRateProvider.SUPPORTED_CURRENCIES) {
      assertThat(Currency.getInstance(currency).getDefaultFractionDigits())
          .isGreaterThanOrEqualTo(0);
    }
  }
}
