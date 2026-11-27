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
public class FinancePlanningController {
  private final FinanceCategoryService categories;
  private final FinanceRecurrenceService recurring;

  public FinancePlanningController(
      FinanceCategoryService categories, FinanceRecurrenceService recurring) {
    this.categories = categories;
    this.recurring = recurring;
  }

  private UUID owner(Principal principal) {
    return UUID.fromString(principal.getName());
  }

  public record CategoryRequest(
      @NotBlank @Pattern(regexp = "INCOME|EXPENSE") String direction,
      @NotBlank @Size(max = 60) String label) {}

  public record Rename(@NotBlank @Size(max = 60) String label) {}

  public record RuleRequest(
      @NotBlank @Pattern(regexp = "INCOME|EXPENSE") String direction,
      @NotBlank @Size(max = 24) String category,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount,
      @Size(max = 120) String description,
      @NotBlank @Pattern(regexp = "DAILY|WEEKLY|MONTHLY") String frequency,
      @NotNull LocalDate startDate,
      LocalDate endDate) {}

  public record RuleEdit(
      @NotBlank @Pattern(regexp = "INCOME|EXPENSE") String direction,
      @NotBlank @Size(max = 24) String category,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount,
      @Size(max = 120) String description,
      LocalDate endDate) {}

  public record State(@NotBlank @Pattern(regexp = "PAUSE|RESUME|CANCEL") String action) {}

  @GetMapping("/category-management")
  public List<FinanceCategoryService.Category> categories(Principal p) {
    return categories.list(owner(p));
  }

  @PostMapping("/category-management")
  @ResponseStatus(HttpStatus.CREATED)
  public FinanceCategoryService.Category createCategory(
      Principal p, @Valid @RequestBody CategoryRequest r) {
    return categories.create(owner(p), r.direction(), r.label());
  }

  @PutMapping("/category-management/{code}")
  public FinanceCategoryService.Category rename(
      Principal p, @PathVariable String code, @Valid @RequestBody Rename r) {
    return categories.rename(owner(p), code, r.label());
  }

  @DeleteMapping("/category-management/{code}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archive(Principal p, @PathVariable String code) {
    categories.archive(owner(p), code);
  }

  @GetMapping("/recurring")
  public List<FinanceRecurrenceService.Rule> rules(Principal p) {
    return recurring.list(owner(p));
  }

  @PostMapping("/recurring")
  @ResponseStatus(HttpStatus.CREATED)
  public FinanceRecurrenceService.Rule rule(Principal p, @Valid @RequestBody RuleRequest r) {
    return recurring.create(
        owner(p),
        r.direction(),
        r.category(),
        r.amount(),
        r.description(),
        r.frequency(),
        r.startDate(),
        r.endDate());
  }

  @PutMapping("/recurring/{id}")
  public FinanceRecurrenceService.Rule edit(
      Principal p, @PathVariable UUID id, @Valid @RequestBody RuleEdit r) {
    return recurring.edit(
        owner(p), id, r.direction(), r.category(), r.amount(), r.description(), r.endDate());
  }

  @PostMapping("/recurring/{id}/state")
  public FinanceRecurrenceService.Rule state(
      Principal p, @PathVariable UUID id, @Valid @RequestBody State r) {
    return recurring.state(owner(p), id, r.action());
  }

  @PostMapping("/recurring/materialize")
  public Map<String, Integer> materialize(Principal p) {
    return Map.of("created", recurring.materialize(owner(p)));
  }
}
