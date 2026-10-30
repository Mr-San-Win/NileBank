package com.vaultbank.transfer;

import com.vaultbank.transaction.TransactionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
  private final TransferService service;

  public TransferController(TransferService service) {
    this.service = service;
  }

  public record TransferRequest(
      @NotNull UUID sourceAccountId,
      @NotBlank @Pattern(regexp = "VB[A-F0-9]{20}") String recipientAccountNumber,
      @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer = 7, fraction = 2)
          BigDecimal amount) {}

  @PostMapping
  public TransactionView transfer(
      Principal p,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody TransferRequest r) {
    return service.transfer(
        UUID.fromString(p.getName()),
        r.sourceAccountId(),
        r.recipientAccountNumber(),
        r.amount(),
        key);
  }
}
