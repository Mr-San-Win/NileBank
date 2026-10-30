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
}
