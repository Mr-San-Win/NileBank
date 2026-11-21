package com.nilebank.exchange;

import com.nilebank.common.ApiException;
import com.nilebank.transaction.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ExchangeService {
  private final LedgerRepository ledger;
  private final ExchangeRepository quotes;
  private final ExchangeRateProvider provider;

  public ExchangeService(
      LedgerRepository ledger, ExchangeRepository quotes, ExchangeRateProvider provider) {
    this.ledger = ledger;
    this.quotes = quotes;
    this.provider = provider;
  }

  public record Rate(String currency, String thbPerUnit) {}

  public record Rates(
      String mode,
      String notice,
      String provider,
      LocalDate rateDate,
      Instant fetchedAt,
      List<Rate> rates) {}

  public Rates rates() {
    var snapshot = provider.fetch();
    return new Rates(
        "REAL_REFERENCE_RATES",
        "Approximate display rates from published reference data; not a live trading feed."
            + " Settlement uses the original snapshot precision, not these display values.",
        snapshot.provider(),
        snapshot.rateDate(),
        snapshot.fetchedAt(),
        ExchangeRateProvider.SUPPORTED_CURRENCIES.stream()
            .map(c -> new Rate(c, snapshot.thbPerUnit().get(c).toPlainString()))
            .toList());
  }

  public record Wallet(String currency, String balance) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Wallet> wallets(UUID owner) {
    UUID account = ledger.ownedId(owner);
    return ExchangeRateProvider.SUPPORTED_CURRENCIES.stream()
        .map(c -> new Wallet(c, ledger.balance(account, c).toPlainString()))
        .toList();
  }

  public record QuoteView(
      UUID id,
      String fromCurrency,
      String toCurrency,
      String amount,
      String receivedAmount,
      String rate,
      Instant expiresAt,
      String status,
      UUID transactionId,
      String reference,
      String notice,
      String provider,
      LocalDate rateDate,
      Instant fetchedAt) {}

  private QuoteView view(ExchangeRepository.Quote q) {
    return new QuoteView(
        q.id(),
        q.fromCurrency(),
        q.toCurrency(),
        q.amount().toPlainString(),
        q.receivedAmount().toPlainString(),
        q.rate().toPlainString(),
        q.expiresAt(),
        q.transactionId() == null ? "OPEN" : "EXECUTED",
        q.transactionId(),
        q.reference(),
        "Simulated money · no fee. The eight-decimal rate is approximate and for display only;"
            + " amount and receivedAmount are the authoritative quote terms. The received amount"
            + " rounds down once to the destination currency's minor unit.",
        q.provider(),
        q.rateDate(),
        q.fetchedAt());
  }

  @Transactional
  public QuoteView quote(UUID owner, String from, String to, BigDecimal amount) {
    amount = Money.validate(amount);
    if (from == null
        || to == null
        || !ExchangeRateProvider.SUPPORTED_CURRENCIES.contains(from)
        || !ExchangeRateProvider.SUPPORTED_CURRENCIES.contains(to)
        || from.equals(to))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Choose two different supported currencies");
    // Fetch before acquiring any account lock; failed networking never touches balances.
    var snapshot = provider.fetch();
    var thbConversion = snapshot.conversion(from, "THB");
    // Cross-multiply to enforce the limit without rounding a normalized THB value.
    if (amount
            .multiply(thbConversion.numerator())
            .compareTo(new BigDecimal("1000000").multiply(thbConversion.denominator()))
        > 0)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Exchange limit is 1,000,000 THB equivalent");
    var conversion = snapshot.conversion(from, to);
    BigDecimal rate =
        conversion.numerator().divide(conversion.denominator(), 8, RoundingMode.HALF_EVEN);
    int destinationScale = Currency.getInstance(to).getDefaultFractionDigits();
    // One final rounding step. The rounded display rate must never determine credited funds.
    BigDecimal received =
        amount
            .multiply(conversion.numerator())
            .divide(conversion.denominator(), destinationScale, RoundingMode.DOWN);
    if (received.signum() <= 0)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Amount is too small after currency rounding");
    var account = ledger.lockOwned(owner);
    account.requireActive();
    var quote =
        new ExchangeRepository.Quote(
            UUID.randomUUID(),
            owner,
            account.id(),
            from,
            to,
            amount,
            received,
            rate,
            Instant.now().plusSeconds(120),
            null,
            null,
            snapshot.provider(),
            snapshot.rateDate(),
            snapshot.fetchedAt());
    quotes.save(quote);
    return view(quote);
  }

  @Transactional
  public QuoteView execute(UUID owner, UUID id) {
    var account = ledger.lockOwned(owner);
    var quote =
        quotes
            .find(id, owner)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Quote not found"));
    if (quote.transactionId() != null) return view(quote);
    account.requireActive();
    if (!quote.expiresAt().isAfter(Instant.now()))
      throw new ApiException(HttpStatus.CONFLICT, "Quote expired; request a fresh quote");
    ledger.adjust(account, quote.fromCurrency(), quote.amount().negate());
    ledger.adjust(account, quote.toCurrency(), quote.receivedAmount());
    var audit =
        ledger.record(
            "EXCHANGE",
            account.id(),
            false,
            quote.amount(),
            quote.fromCurrency(),
            quote.toCurrency(),
            quote.receivedAmount(),
            "Exchange "
                + quote.fromCurrency()
                + " → "
                + quote.toCurrency()
                + " · ECB "
                + quote.rateDate());
    quotes.executed(id, audit.id());
    return view(quotes.find(id, owner).orElseThrow());
  }
}
