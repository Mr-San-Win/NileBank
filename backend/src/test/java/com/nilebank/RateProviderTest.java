package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nilebank.common.ApiException;
import com.nilebank.exchange.FrankfurterRateProvider;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateProviderTest {
  final FrankfurterRateProvider provider =
      new FrankfurterRateProvider(JsonMapper.builder().findAndAddModules().build());
  final Instant now = Instant.parse("2026-10-30T10:00:00Z");

  @Test
  void parsesPublishedRatesAndDerivesCrossRateExactly() {
    var r =
        provider.parse(
            "[{\"date\":\"2026-10-29\",\"base\":\"EUR\",\"quote\":\"THB\",\"rate\":38},{\"date\":\"2026-10-29\",\"base\":\"EUR\",\"quote\":\"USD\",\"rate\":1.25}]",
            now);
    assertThat(r.thbPerUnit().get("USD")).isEqualByComparingTo("30.4");
    assertThat(r.rateDate().toString()).isEqualTo("2026-10-29");
  }

  @Test
  void rejectsMissingStaleMixedOrInvalidRatesWithoutFallback() {
    for (String body :
        java.util.List.of(
            "[]",
            "[{\"date\":\"2020-01-01\",\"base\":\"EUR\",\"quote\":\"THB\",\"rate\":38},{\"date\":\"2020-01-01\",\"base\":\"EUR\",\"quote\":\"USD\",\"rate\":1.25}]",
            "[{\"date\":\"2026-10-29\",\"base\":\"EUR\",\"quote\":\"THB\",\"rate\":-38},{\"date\":\"2026-10-29\",\"base\":\"EUR\",\"quote\":\"USD\",\"rate\":1.25}]"))
      assertThatThrownBy(() -> provider.parse(body, now)).isInstanceOf(ApiException.class);
  }

  @Test
  void retainsPublishedDecimalRatiosWithoutNormalizingSettlementWeights() {
    var r =
        provider.parse(
            "[{\"date\":\"2026-10-29\",\"base\":\"EUR\",\"quote\":\"THB\",\"rate\":1.234567890123456789},{\"date\":\"2026-10-29\",\"base\":\"EUR\",\"quote\":\"USD\",\"rate\":0.600000000000000001}]",
            now);
    assertThat(r.conversion("THB", "USD").numerator()).isEqualByComparingTo("0.600000000000000001");
    assertThat(r.conversion("THB", "USD").denominator())
        .isEqualByComparingTo("1.234567890123456789");
    assertThat(r.conversion("USD", "THB").numerator()).isEqualByComparingTo("1.234567890123456789");
    assertThat(r.conversion("USD", "THB").denominator())
        .isEqualByComparingTo("0.600000000000000001");
    assertThat(r.conversion("EUR", "USD").denominator()).isEqualByComparingTo("1");
    assertThat(r.thbPerUnit().get("USD").scale()).isEqualTo(8);
  }

  @Test
  void parsesCompleteSevenCurrencyReferenceSnapshotAndRejectsIncompleteResponse() throws Exception {
    var mapper = JsonMapper.builder().findAndAddModules().build();
    var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
    for (var code : com.nilebank.exchange.SupportedCurrency.codes()) {
      if (code.equals("EUR")) continue;
      rows.add(
          java.util.Map.of(
              "date",
              "2026-10-29",
              "base",
              "EUR",
              "quote",
              code,
              "rate",
              new java.math.BigDecimal("1.234567890123456789")));
    }
    var parsed =
        provider.parse(
            mapper.writeValueAsString(rows),
            now,
            com.nilebank.exchange.SupportedCurrency.codes(),
            "CBM deterministic fixture");
    assertThat(parsed.currencies()).hasSize(7);
    assertThat(parsed.unitsPerEuro().get("MMK")).isEqualByComparingTo("1.234567890123456789");
    rows.removeFirst();
    assertThatThrownBy(
            () ->
                provider.parse(
                    mapper.writeValueAsString(rows),
                    now,
                    com.nilebank.exchange.SupportedCurrency.codes(),
                    "CBM deterministic fixture"))
        .isInstanceOf(ApiException.class);
  }
}
