package com.nilebank.savings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/savings")
public class SavingsController {
  private final SavingsService service;

  public SavingsController(SavingsService service) {
    this.service = service;
  }

  public record Create(
      @NotBlank @Size(max = 80) String name,
      @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal target) {}

  public record Edit(
      @NotBlank @Size(max = 80) String name,
      @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal target,
      @NotBlank @Pattern(regexp = "ACTIVE|FROZEN") String status) {}

  public record Move(
      @NotNull UUID savingsId,
      @NotBlank @Pattern(regexp = "IN|OUT") String direction,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount) {}

  private UUID owner(Principal principal) {
    return UUID.fromString(principal.getName());
  }

  @GetMapping("/accounts")
  public List<SavingsService.AccountView> accounts(Principal p) {
    return service.list(owner(p));
  }

  @PostMapping("/accounts")
  @ResponseStatus(HttpStatus.CREATED)
  public SavingsService.AccountView create(Principal p, @Valid @RequestBody Create r) {
    return service.create(owner(p), r.name(), r.target());
  }

  @PutMapping("/accounts/{id}")
  public SavingsService.AccountView edit(
      Principal p, @PathVariable UUID id, @Valid @RequestBody Edit r) {
    return service.edit(owner(p), id, r.name(), r.target(), r.status());
  }

  @PostMapping("/movements")
  public SavingsService.MovementView move(
      Principal p, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Move r) {
    return service.move(owner(p), r.savingsId(), r.direction(), r.amount(), key);
  }

  @GetMapping("/accounts/{id}/history")
  public SavingsService.History history(
      Principal p,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.history(owner(p), id, page, size);
  }
}
