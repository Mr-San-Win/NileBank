package com.nilebank.exchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.common.ApiException;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class FrankfurterRateProvider implements ExchangeRateProvider {
  private static final URI ENDPOINT =
      URI.create("https://api.frankfurter.dev/v2/rates?base=EUR&quotes=THB,USD&providers=ecb");
  private final ObjectMapper json;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(4))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public FrankfurterRateProvider(ObjectMapper json) {
    this.json = json;
  }

  public record Row(LocalDate date, String base, String quote, BigDecimal rate) {}

  @Override
  public Snapshot fetch() {
    try {
      var request =
          HttpRequest.newBuilder(ENDPOINT)
              .timeout(Duration.ofSeconds(8))
              .header("Accept", "application/json")
              .header("Cache-Control", "no-cache")
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("Upstream HTTP " + response.statusCode());
      return parse(response.body(), Instant.now());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public Snapshot parse(String body, Instant now) {
    try {
      List<Row> rows = json.readValue(body, new TypeReference<List<Row>>() {});
      if (rows.size() != 2) throw new IllegalArgumentException("Incomplete rate set");
      Map<String, BigDecimal> perEuro = new HashMap<>();
      LocalDate day = null;
      for (Row row : rows) {
        if (!"EUR".equals(row.base())
            || !Set.of("THB", "USD").contains(row.quote())
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
      return new Snapshot(
          "Frankfurter / European Central Bank",
          day,
          now,
          Map.of(
              "THB",
              BigDecimal.ONE,
              "EUR",
              perEuro.get("THB"),
              "USD",
              perEuro.get("THB").divide(perEuro.get("USD"), 8, RoundingMode.HALF_EVEN)));
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
