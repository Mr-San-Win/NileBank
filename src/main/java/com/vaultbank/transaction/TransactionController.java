package com.vaultbank.transaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final BankingService service;
    public TransactionController(BankingService service){this.service=service;}
    public record Operation(@NotNull UUID accountId,@NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer=7,fraction=2) BigDecimal amount){}
    @PostMapping("/deposit") @ResponseStatus(HttpStatus.CREATED)
    public TransactionView deposit(Principal p,@Valid @RequestBody Operation r){return service.deposit(UUID.fromString(p.getName()),r.accountId(),r.amount());}
    @PostMapping("/withdraw") @ResponseStatus(HttpStatus.CREATED)
    public TransactionView withdraw(Principal p,@Valid @RequestBody Operation r){return service.withdraw(UUID.fromString(p.getName()),r.accountId(),r.amount());}
    @GetMapping
    public BankingService.History history(Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size){return service.history(UUID.fromString(p.getName()),page,size);}
}
