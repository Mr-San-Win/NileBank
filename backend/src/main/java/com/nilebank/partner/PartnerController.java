package com.nilebank.partner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/partners")
public class PartnerController {
  private final PartnerService service;

  public PartnerController(PartnerService service) {
    this.service = service;
  }

  public record LinkRequest(
      @NotBlank @Pattern(regexp = "HARBOR|ORCHID") String bankCode,
      @AssertTrue boolean demoConsent) {}

  public record MoveRequest(
      @NotNull UUID connectionId,
      @NotBlank @Pattern(regexp = "IN|OUT") String direction,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount) {}

  @GetMapping("/banks")
  public List<PartnerService.Bank> banks() {
    return service.catalog();
  }

  @GetMapping("/connections")
  public List<PartnerService.ConnectionView> connections(Principal p) {
    return service.connections(UUID.fromString(p.getName()));
  }

  @PostMapping("/connections")
  public PartnerService.ConnectionView link(Principal p, @Valid @RequestBody LinkRequest r) {
    return service.link(UUID.fromString(p.getName()), r.bankCode(), r.demoConsent());
  }

  @PostMapping("/connections/{id}/disconnect")
  public PartnerService.ConnectionView disconnect(Principal p, @PathVariable UUID id) {
    return service.disconnect(UUID.fromString(p.getName()), id);
  }

  @PostMapping("/transfers")
  public PartnerService.MovementView transfer(
      Principal p,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody MoveRequest r) {
    return service.transfer(
        UUID.fromString(p.getName()), r.connectionId(), r.direction(), r.amount(), key);
  }
}
