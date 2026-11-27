package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nilebank.auth.AuthenticationRateLimitFilter;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class AuthenticationRateLimitTest {
  MockHttpServletResponse attempt(AuthenticationRateLimitFilter filter, String address, String path)
      throws Exception {
    var request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(address);
    request.addHeader("X-Forwarded-For", java.util.UUID.randomUUID().toString());
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void limitsAttemptsDespiteSpoofedForwardedHeaders() throws Exception {
    var filter =
        new AuthenticationRateLimitFilter(
            JsonMapper.builder().findAndAddModules().build(), Clock.systemUTC(), 2, 100);
    assertThat(attempt(filter, "192.0.2.1", "/api/v1/auth/login").getStatus()).isEqualTo(200);
    assertThat(attempt(filter, "192.0.2.1", "/api/v1/auth/login").getStatus()).isEqualTo(200);
    var blocked = attempt(filter, "192.0.2.1", "/api/v1/auth/register");
    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
    assertThat(attempt(filter, "192.0.2.2", "/api/v1/auth/login").getStatus()).isEqualTo(200);
  }

  @Test
  void boundsMemoryAndDoesNotLimitOrdinaryBanking() throws Exception {
    var filter =
        new AuthenticationRateLimitFilter(
            JsonMapper.builder().findAndAddModules().build(), Clock.systemUTC(), 1, 1);
    assertThat(attempt(filter, "192.0.2.1", "/api/v1/auth/security/password").getStatus())
        .isEqualTo(200);
    assertThat(attempt(filter, "192.0.2.2", "/api/v1/auth/login").getStatus()).isEqualTo(429);
    assertThat(attempt(filter, "192.0.2.2", "/api/v1/accounts/me").getStatus()).isEqualTo(200);
  }

  @Test
  void newMinuteRestoresAccess() throws Exception {
    class MutableClock extends Clock {
      long millis;

      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return Instant.ofEpochMilli(millis);
      }

      @Override
      public long millis() {
        return millis;
      }
    }
    var clock = new MutableClock();
    var filter =
        new AuthenticationRateLimitFilter(
            JsonMapper.builder().findAndAddModules().build(), clock, 1, 1);
    assertThat(attempt(filter, "one", "/api/v1/auth/login").getStatus()).isEqualTo(200);
    assertThat(attempt(filter, "one", "/api/v1/auth/login").getStatus()).isEqualTo(429);
    clock.millis = 60000;
    assertThat(attempt(filter, "two", "/api/v1/auth/login").getStatus()).isEqualTo(200);
  }
}
