package com.vaultbank.account;

import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
  private final AccountService service;

  public AccountController(AccountService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AccountService.AccountView create(Principal p) {
    return service.create(UUID.fromString(p.getName()));
  }

  @GetMapping("/me")
  public AccountService.AccountView mine(Principal p) {
    return service.mine(UUID.fromString(p.getName()));
  }
}
