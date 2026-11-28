package com.nilebank.exchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.common.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Read-only display data. Never participates in quotes, balances or settlement. */
@Service
public class RateHistoryService {
  public record Point(LocalDate date, BigDecimal rate) {}

  public record History(
      String provider,
      String fromCurrency,
      String toCurrency,
      LocalDate from,
      LocalDate to,
      Instant fetchedAt,
      List<Point> points) {
    public History {
      points = List.copyOf(points);
    }
  }

  private record Key(String from, String to, int days) {}

  private final Map<Key, History> cache = new ConcurrentHashMap<>();
  private final ObjectMapper json;
  private final String source;
  private final URI origin;
  private final Clock clock;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(4))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Autowired
  public RateHistoryService(
      ObjectMapper json, @Value("${nilebank.fx.reference-provider:cbm}") String source) {
    this(json, source, URI.create("https://api.frankfurter.dev"), Clock.systemUTC());
  }

  RateHistoryService(ObjectMapper json, String source, URI origin, Clock clock) {
    if (!Set.of("cbm", "ecb").contains(source))
      throw new IllegalArgumentException("Unsupported reference provider");
    this.json = json;
    this.source = source;
    this.origin = origin;
    this.clock = clock;
  }

  public History history(String from, String to, int days) {
    if (!SupportedCurrency.codes().contains(from)
        || !SupportedCurrency.codes().contains(to)
        || from.equals(to)
        || !Set.of(7, 30, 90).contains(days)
        || (source.equals("ecb") && (from.equals("MMK") || to.equals("MMK"))))
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Choose two supported currencies and a 7, 30 or 90 day period.");
    Key key = new Key(from, to, days);
    Instant now = clock.instant();
    LocalDate end = LocalDate.now(clock), start = end.minusDays(days - 1);
    History previous = cache.get(key);
    if (previous != null
        && previous.to().equals(end)
        && previous.fetchedAt().plusSeconds(300).isAfter(now)) return previous;
    try {
      URI uri =
          origin.resolve(
              "/v2/providers/"
                  + source
                  + "/rates?base="
                  + from
                  + "&quotes="
                  + to
                  + "&from="
                  + start
                  + "&to="
                  + end);
      var request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(8))
              .header("Accept", "application/json")
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || response.body().length() > 65536) throw unavailable();
      History result = parse(response.body(), from, to, start, end, now);
      cache.put(key, result);
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception e) {
      throw unavailable();
    }
  }

  History parse(String body, String from, String to, LocalDate start, LocalDate end, Instant now) {
    try {
      List<FrankfurterRateProvider.Row> rows =
          json.readValue(body, new TypeReference<List<FrankfurterRateProvider.Row>>() {});
      if (rows == null || rows.isEmpty() || rows.size() > 91) throw unavailable();
      Map<LocalDate, BigDecimal> daily = new TreeMap<>();
      for (var row : rows) {
        if (row == null
            || !from.equals(row.base())
            || !to.equals(row.quote())
            || row.date() == null
            || row.date().isBefore(start)
            || row.date().isAfter(end)
            || row.rate() == null
            || row.rate().signum() <= 0
            || row.rate().precision() > 30
            || Math.abs((long) row.rate().scale()) > 18
            || daily.putIfAbsent(row.date(), row.rate()) != null) throw unavailable();
      }
      return new History(
          "Frankfurter / "
              + (source.equals("cbm")
                  ? "Central Bank of Myanmar (official reference)"
                  : "European Central Bank"),
          from,
          to,
          start,
          end,
          now,
          daily.entrySet().stream().map(e -> new Point(e.getKey(), e.getValue())).toList());
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Historical reference rates are unavailable. Try again later; no simulated data is"
            + " substituted.");
  }
}
