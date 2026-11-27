package com.nilebank.exchange;

import com.nilebank.common.ApiException;
import com.nilebank.transaction.Money;
import java.math.*;
import java.util.*;
import org.springframework.http.HttpStatus;

public enum SupportedCurrency {
  THB,
  USD,
  EUR,
  MMK,
  SGD,
  JPY,
  CNY;

  public static List<String> codes() {
    return Arrays.stream(values()).map(Enum::name).toList();
  }

  public static int scale(String code) {
    if (code == null || !codes().contains(code))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported currency");
    int scale = Currency.getInstance(code).getDefaultFractionDigits();
    if (scale < 0 || scale > 2) throw new IllegalStateException("Unsupported currency precision");
    return scale;
  }

  public static BigDecimal amount(String code, BigDecimal amount) {
    amount = Money.validate(amount);
    try {
      return amount.setScale(scale(code), RoundingMode.UNNECESSARY);
    } catch (ArithmeticException precision) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Amount exceeds the currency's fractional precision");
    }
  }

  public static String display(String code, BigDecimal value) {
    return value.setScale(scale(code), RoundingMode.UNNECESSARY).toPlainString();
  }
}
