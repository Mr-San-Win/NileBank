package com.nilebank.exchange;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/exchange")
public class ExchangeController {
  private final ExchangeService service;

  public ExchangeController(ExchangeService service) {
    this.service = service;
  }

  public record QuoteRequest(
      @NotNull @Pattern(regexp = "THB|USD|EUR") String fromCurrency,
      @NotNull @Pattern(regexp = "THB|USD|EUR") String toCurrency,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount) {}

  @GetMapping("/rates")
  public ExchangeService.Rates rates() {
    return service.rates();
  }

  @GetMapping("/wallets")
  public List<ExchangeService.Wallet> wallets(Principal p) {
    return service.wallets(UUID.fromString(p.getName()));
  }

  @PostMapping("/quotes")
  public ExchangeService.QuoteView quote(Principal p, @Valid @RequestBody QuoteRequest r) {
    return service.quote(
        UUID.fromString(p.getName()), r.fromCurrency(), r.toCurrency(), r.amount());
  }

  @PostMapping("/quotes/{id}/execute")
  public ExchangeService.QuoteView execute(Principal p, @PathVariable UUID id) {
    return service.execute(UUID.fromString(p.getName()), id);
  }
}
