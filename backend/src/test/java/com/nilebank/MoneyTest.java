package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.common.ApiException;
import com.nilebank.transaction.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test
  void exactDecimalArithmeticAndBoundaries() {
    assertThat(Money.validate(new BigDecimal("0.10")).add(Money.validate(new BigDecimal("0.20"))))
        .isEqualByComparingTo("0.30");
    assertThat(Money.validate(new BigDecimal("1000000"))).isEqualTo(new BigDecimal("1000000.00"));
    assertThat(Money.validate(new BigDecimal("0.01"))).isEqualTo(new BigDecimal("0.01"));
  }

  @Test
  void nullAndExcessivePrecisionRejected() {
    assertThatThrownBy(() -> Money.validate(null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Money.validate(new BigDecimal("1.000")))
        .isInstanceOf(ApiException.class);
  }
}
