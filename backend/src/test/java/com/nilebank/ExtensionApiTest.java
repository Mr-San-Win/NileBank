package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.account.AccountService;
import com.nilebank.exchange.ExchangeRateProvider;
import com.nilebank.transaction.BankingService;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

class ExtensionApiTest extends PostgresTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @MockitoBean ExchangeRateProvider provider;

  @BeforeEach
  void rates() {
    when(provider.fetch())
        .thenReturn(
            new ExchangeRateProvider.Snapshot(
                "HTTP test fixture",
                LocalDate.now(ZoneOffset.UTC),
                Instant.now(),
                Map.of(
                    "THB",
                    BigDecimal.ONE,
                    "USD",
                    new BigDecimal("35"),
                    "EUR",
                    new BigDecimal("38"))));
  }

  UUID owner() {
    return users
        .saveAndFlush(
            new BankUser("API Student", UUID.randomUUID() + "@example.test", "not-for-auth"))
        .getId();
  }

  @Test
  void staticModuleIsPublicButFeatureApisRequireAuthenticationAndCsrf() throws Exception {
    mvc.perform(get("/extensions.js")).andExpect(status().isOk());
    for (String path :
        List.of("/api/v1/exchange/rates", "/api/v1/partners/banks", "/api/v1/finance/summary"))
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
    var o = owner();
    mvc.perform(
            post("/api/v1/finance/entries")
                .with(user(o.toString()))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/partners/connections")
                .with(user(o.toString()))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void exchangeApiReturnsRealSourceMetadataAndPersistsCurrencyHistory() throws Exception {
    var o = owner();
    var a = accounts.create(o);
    banking.deposit(o, a.id(), new BigDecimal("350"));
    var quote =
        mvc.perform(
                post("/api/v1/exchange/quotes")
                    .with(user(o.toString()))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        "{\"fromCurrency\":\"THB\",\"toCurrency\":\"USD\",\"amount\":\"350\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.receivedAmount").value("10.00"))
            .andExpect(jsonPath("$.provider").value("HTTP test fixture"))
            .andExpect(jsonPath("$.rateDate").exists())
            .andReturn();
    String id = json.readTree(quote.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(
            post("/api/v1/exchange/quotes/" + id + "/execute")
                .with(user(o.toString()))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXECUTED"));
    mvc.perform(get("/api/v1/transactions").with(user(o.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].type").value("EXCHANGE"))
        .andExpect(jsonPath("$.content[0].counterCurrency").value("USD"));
  }

  @Test
  void partnerAndFinanceApiValidationAndOwnershipRemainEnforced() throws Exception {
    var o = owner();
    accounts.create(o);
    mvc.perform(
            post("/api/v1/partners/connections")
                .with(user(o.toString()))
                .with(csrf())
                .contentType("application/json")
                .content("{\"bankCode\":\"HARBOR\",\"demoConsent\":false}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/partners/connections")
                .with(user(o.toString()))
                .with(csrf())
                .contentType("application/json")
                .content("{\"bankCode\":\"HARBOR\",\"demoConsent\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value("5000.00"));
    String body =
        json.writeValueAsString(
            Map.of(
                "direction",
                "EXPENSE",
                "category",
                "FOOD",
                "amount",
                "25.50",
                "description",
                "Lunch",
                "occurredOn",
                LocalDate.now(ZoneId.of("Asia/Bangkok")).toString()));
    var result =
        mvc.perform(
                post("/api/v1/finance/entries")
                    .with(user(o.toString()))
                    .with(csrf())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount").value("25.50"))
            .andReturn();
    String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(delete("/api/v1/finance/entries/" + id).with(user(owner().toString())).with(csrf()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/finance/summary").with(user(o.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expenses").value("25.50"))
        .andExpect(jsonPath("$.savingsRate").isEmpty());
  }
}
