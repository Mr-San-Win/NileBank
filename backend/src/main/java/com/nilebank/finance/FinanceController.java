package com.nilebank.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
  private final FinanceService service;

  public FinanceController(FinanceService service) {
    this.service = service;
  }

  public record EntryRequest(
      @NotBlank @Pattern(regexp = "INCOME|EXPENSE") String direction,
      @NotBlank @Size(max = 24) String category,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount,
      @Size(max = 120) String description,
      @NotNull LocalDate occurredOn) {}

  public record BudgetRequest(
      @NotBlank String month,
      @NotBlank String category,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount) {}

  public record GoalRequest(
      @NotBlank @Size(max = 80) String name,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal target) {}

  public record Allocation(
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount) {}

  private UUID owner(Principal p) {
    return UUID.fromString(p.getName());
  }

  @GetMapping("/categories")
  public FinanceService.Categories categories() {
    return service.categories();
  }

  @PostMapping("/entries")
  @ResponseStatus(HttpStatus.CREATED)
  public FinanceService.EntryView entry(Principal p, @Valid @RequestBody EntryRequest r) {
    return service.add(
        owner(p), r.direction(), r.category(), r.amount(), r.description(), r.occurredOn());
  }

  @GetMapping("/entries")
  public FinanceService.Entries entries(
      Principal p,
      @RequestParam(required = false) String month,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.entries(owner(p), month, page, size);
  }

  @DeleteMapping("/entries/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(Principal p, @PathVariable UUID id) {
    service.delete(owner(p), id);
  }

  @PostMapping("/budgets")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void budget(Principal p, @Valid @RequestBody BudgetRequest r) {
    service.budget(owner(p), r.month(), r.category(), r.amount());
  }

  @GetMapping("/summary")
  public FinanceService.Summary summary(Principal p, @RequestParam(required = false) String month) {
    return service.summary(owner(p), month);
  }

  @GetMapping("/goals")
  public List<FinanceService.GoalView> goals(Principal p) {
    return service.goals(owner(p));
  }

  @PostMapping("/goals")
  @ResponseStatus(HttpStatus.CREATED)
  public FinanceService.GoalView goal(Principal p, @Valid @RequestBody GoalRequest r) {
    return service.goal(owner(p), r.name(), r.target());
  }

  @PostMapping("/goals/{id}/allocations")
  public FinanceService.GoalView allocate(
      Principal p, @PathVariable UUID id, @Valid @RequestBody Allocation r) {
    return service.contribute(owner(p), id, r.amount());
  }
}
