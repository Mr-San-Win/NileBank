package com.nilebank.exchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.common.ApiException;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class FrankfurterRateProvider implements ExchangeRateProvider {
  private final String source;
  private final List<String> currencies;
  private final URI endpoint;
  private final ObjectMapper json;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(4))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public FrankfurterRateProvider(ObjectMapper json) {
    this(json, "cbm");
  }

  @Autowired
  public FrankfurterRateProvider(
      ObjectMapper json, @Value("${nilebank.fx.reference-provider:cbm}") String source) {
    if (!Set.of("cbm", "ecb").contains(source))
      throw new IllegalArgumentException("Unsupported reference provider");
    this.json = json;
    this.source = source;
    this.currencies =
        SUPPORTED_CURRENCIES.stream()
            .filter(c -> !source.equals("ecb") || !c.equals("MMK"))
            .toList();
    this.endpoint =
        URI.create(
            "https://api.frankfurter.dev/v2/rates?base=EUR&quotes="
                + String.join(",", currencies.stream().filter(c -> !c.equals("EUR")).toList())
                + "&providers="
                + source);
  }

  public record Row(LocalDate date, String base, String quote, BigDecimal rate) {}

  @Override
  public Snapshot fetch() {
    try {
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(8))
              .header("Accept", "application/json")
              .header("Cache-Control", "no-cache")
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("Upstream HTTP " + response.statusCode());
      return parse(
          response.body(),
          Instant.now(),
          currencies,
          source.equals("cbm")
              ? "Frankfurter / Central Bank of Myanmar (official reference)"
              : "Frankfurter / European Central Bank");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public Snapshot parse(String body, Instant now) {
    return parse(body, now, LEGACY_CURRENCIES, "Frankfurter / European Central Bank");
  }

  public Snapshot parse(String body, Instant now, List<String> declared, String attribution) {
    try {
      List<Row> rows = json.readValue(body, new TypeReference<List<Row>>() {});
      if (rows.size() != declared.size() - 1)
        throw new IllegalArgumentException("Incomplete rate set");
      Map<String, BigDecimal> perEuro = new HashMap<>();
      LocalDate day = null;
      for (Row row : rows) {
        if (!"EUR".equals(row.base())
            || row.quote() == null
            || !declared.contains(row.quote())
            || "EUR".equals(row.quote())
            || row.rate() == null
            || row.rate().signum() <= 0
            || row.date() == null) throw new IllegalArgumentException("Invalid rate row");
        if (day != null && !day.equals(row.date()))
          throw new IllegalArgumentException("Mixed publication dates");
        day = row.date();
        if (perEuro.put(row.quote(), row.rate()) != null)
          throw new IllegalArgumentException("Duplicate currency");
      }
      LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
      if (day.isAfter(today) || day.isBefore(today.minusDays(7)))
        throw new IllegalArgumentException("Stale or future rates");
      perEuro.put("EUR", BigDecimal.ONE);
      Map<String, BigDecimal> display = new HashMap<>();
      for (String currency : declared)
        display.put(
            currency,
            currency.equals("THB")
                ? BigDecimal.ONE
                : perEuro.get("THB").divide(perEuro.get(currency), 8, RoundingMode.HALF_EVEN));
      return new Snapshot(attribution, day, now, display, perEuro, declared);
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Real exchange rates are unavailable or stale. Refresh again later; no fixed fallback is"
            + " used.");
  }
}
