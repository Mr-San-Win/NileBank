package com.nilebank.exchange;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateHistoryServiceTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final Instant now = Instant.parse("2026-11-28T12:00:00Z");
  final LocalDate start = LocalDate.of(2026, 11, 22), end = LocalDate.of(2026, 11, 28);
  final String rows =
      "[{\"date\":\"2026-11-27\",\"base\":\"USD\",\"quote\":\"THB\",\"rate\":35.125},{\"date\":\"2026-11-23\",\"base\":\"USD\",\"quote\":\"THB\",\"rate\":35.001}]";

  RateHistoryService service() {
    return new RateHistoryService(
        json, "cbm", URI.create("http://127.0.0.1:1"), Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void preservesRealDatesPrecisionAndSortsWithoutInventingMissingDays() {
    var h = service().parse(rows, "USD", "THB", start, end, now);
    assertThat(h.points()).hasSize(2);
    assertThat(h.points().getFirst().date()).isEqualTo(LocalDate.of(2026, 11, 23));
    assertThat(h.points().getFirst().rate()).isEqualByComparingTo("35.001");
    assertThatThrownBy(() -> h.points().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsDuplicateDates() {
    assertThatThrownBy(
            () ->
                service()
                    .parse(rows.replace("2026-11-23", "2026-11-27"), "USD", "THB", start, end, now))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsOutOfRangeAndFutureDates() {
    for (String day : new String[] {"2026-11-21", "2026-11-29"})
      assertThatThrownBy(
              () -> service().parse(rows.replace("2026-11-27", day), "USD", "THB", start, end, now))
          .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsWrongPairsAndInvalidWeights() {
    for (String invalid :
        new String[] {
          rows.replace("USD", "EUR"),
          rows.replace("THB", "JPY"),
          rows.replace("35.125", "0"),
          rows.replace("35.125", "-1"),
          rows.replace("35.125", "null"),
          rows.replace("35.125", "1e999")
        })
      assertThatThrownBy(() -> service().parse(invalid, "USD", "THB", start, end, now))
          .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsMissingMalformedDataWithoutExposingUpstreamDetails() {
    for (String invalid : new String[] {"null", "[]", "{private upstream failure}", "[null]"})
      assertThatThrownBy(() -> service().parse(invalid, "USD", "THB", start, end, now))
          .isInstanceOf(ApiException.class)
          .hasMessageNotContaining("private");
  }

  @Test
  void rejectsUnsupportedPairsAndUnboundedPeriodsBeforeNetworkAccess() {
    for (int days : new int[] {0, 1, 31, 365, 10000})
      assertThatThrownBy(() -> service().history("USD", "THB", days))
          .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service().history("USD", "USD", 7)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service().history("BAD", "USD", 7)).isInstanceOf(ApiException.class);
    var ecb =
        new RateHistoryService(
            json, "ecb", URI.create("http://127.0.0.1:1"), Clock.fixed(now, ZoneOffset.UTC));
    assertThatThrownBy(() -> ecb.history("MMK", "USD", 7)).isInstanceOf(ApiException.class);
  }

  @Test
  void fetchesPinnedSourceAndCachesSuccessfulHistory() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/v2/providers/cbm/rates",
        exchange -> {
          calls.incrementAndGet();
          String query = exchange.getRequestURI().getQuery();
          assertThat(query).contains("base=USD", "quotes=THB", "from=2026-11-22", "to=2026-11-28");
          byte[] body = rows.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var service =
          new RateHistoryService(
              json,
              "cbm",
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Clock.fixed(now, ZoneOffset.UTC));
      assertThat(service.history("USD", "THB", 7)).isSameAs(service.history("USD", "THB", 7));
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void providerFailureNeverCreatesFallbackHistory() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.start();
    try {
      var service =
          new RateHistoryService(
              json,
              "cbm",
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Clock.fixed(now, ZoneOffset.UTC));
      assertThatThrownBy(() -> service.history("USD", "THB", 7))
          .isInstanceOf(ApiException.class)
          .hasMessageNotContaining("503");
    } finally {
      server.stop(0);
    }
  }
}
