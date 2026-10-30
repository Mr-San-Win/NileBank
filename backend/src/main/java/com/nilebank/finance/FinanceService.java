package com.nilebank.finance;

import com.nilebank.common.ApiException;
import com.nilebank.transaction.Money;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class FinanceService {
  public static final List<String> INCOME = List.of("SALARY", "FREELANCE", "OTHER_INCOME");
  public static final List<String> EXPENSE =
      List.of(
          "FOOD",
          "HOUSING",
          "TRANSPORT",
          "SHOPPING",
          "HEALTH",
          "EDUCATION",
          "ENTERTAINMENT",
          "OTHER");
  private final FinanceRepository repository;

  public FinanceService(FinanceRepository repository) {
    this.repository = repository;
  }

  public record Categories(List<String> income, List<String> expense, String notice) {}

  public Categories categories() {
    return new Categories(
        INCOME,
        EXPENSE,
        "THB tracker records only. Transfers, exchange and bank top-ups are not income or spending."
            + " Tracker entries and savings allocations do not change bank balances.");
  }

  public static YearMonth month(String input) {
    try {
      YearMonth month =
          input == null || input.isBlank()
              ? YearMonth.now(ZoneId.of("Asia/Bangkok"))
              : YearMonth.parse(input);
      if (month.getYear() < 2000 || month.getYear() > 2100) throw new IllegalArgumentException();
      return month;
    } catch (Exception e) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Use a month in YYYY-MM format between 2000 and 2100");
    }
  }

  public record EntryView(
      UUID id,
      String direction,
      String category,
      String amount,
      String description,
      LocalDate occurredOn) {}

  private EntryView view(FinanceRepository.Entry e) {
    return new EntryView(
        e.id(),
        e.direction(),
        e.category(),
        e.amount().toPlainString(),
        e.description(),
        e.occurredOn());
  }

  @Transactional
  public EntryView add(
      UUID owner,
      String direction,
      String category,
      BigDecimal amount,
      String description,
      LocalDate day) {
    amount = Money.validate(amount);
    if (category == null
        || !("INCOME".equals(direction) && INCOME.contains(category)
            || "EXPENSE".equals(direction) && EXPENSE.contains(category)))
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Choose a category matching income or expense");
    if (day == null
        || day.getYear() < 2000
        || day.isAfter(LocalDate.now(ZoneId.of("Asia/Bangkok"))))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Entry date must be between 2000 and today");
    description = description == null ? "" : description.strip();
    if (description.length() > 120)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Description is limited to 120 characters");
    return view(repository.add(owner, direction, category, amount, description, day));
  }

  public record Entries(
      List<EntryView> content, int page, int size, long totalElements, int totalPages) {}

  @Transactional(readOnly = true)
  public Entries entries(UUID owner, String month, int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Page must be nonnegative and size between 1 and 100");
    var period = month(month);
    long total = repository.count(owner, period);
    return new Entries(
        repository.entries(owner, period, page, size).stream().map(this::view).toList(),
        page,
        size,
        total,
        (int) ((total + size - 1) / size));
  }

  @Transactional
  public void delete(UUID owner, UUID id) {
    if (!repository.delete(owner, id))
      throw new ApiException(HttpStatus.NOT_FOUND, "Tracker entry not found");
  }

  @Transactional
  public void budget(UUID owner, String month, String category, BigDecimal amount) {
    if (!EXPENSE.contains(category))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Budgets require an expense category");
    repository.budget(owner, month(month), category, Money.validate(amount));
  }

  public record GoalView(
      UUID id, String name, String target, String saved, String remaining, String percent) {}

  private GoalView view(FinanceRepository.Goal goal) {
    return new GoalView(
        goal.id(),
        goal.name(),
        goal.target().toPlainString(),
        goal.saved().toPlainString(),
        goal.target().subtract(goal.saved()).toPlainString(),
        goal.saved()
            .multiply(new BigDecimal("100"))
            .divide(goal.target(), 0, RoundingMode.HALF_UP)
            .toPlainString());
  }

  @Transactional(readOnly = true)
  public List<GoalView> goals(UUID owner) {
    return repository.goals(owner).stream().map(this::view).toList();
  }

  @Transactional
  public GoalView goal(UUID owner, String name, BigDecimal target) {
    if (name == null || name.isBlank() || name.strip().length() > 80)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Goal name must contain 1–80 characters");
    return view(repository.createGoal(owner, name.strip(), Money.validate(target)));
  }

  @Transactional
  public GoalView contribute(UUID owner, UUID id, BigDecimal amount) {
    amount = Money.validate(amount);
    var goal =
        repository
            .lockGoal(owner, id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Savings goal not found"));
    BigDecimal next = goal.saved().add(amount);
    if (next.compareTo(goal.target()) > 0)
      throw new ApiException(
          HttpStatus.CONFLICT, "Allocation exceeds the amount remaining for this goal");
    repository.saved(id, next);
    return view(new FinanceRepository.Goal(goal.id(), goal.name(), goal.target(), next));
  }

  public record CategoryView(
      String category, String spent, String budget, String remaining, String percent) {}

  public record Insight(String level, String title, String detail, String basis) {}

  public record Summary(
      String month,
      String currency,
      String income,
      String expenses,
      String net,
      String savingsRate,
      long entryCount,
      String previousExpenses,
      List<CategoryView> categories,
      List<Insight> insights) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Summary summary(UUID owner, String month) {
    var period = month(month);
    var totals = repository.totals(owner, period);
    BigDecimal income = BigDecimal.ZERO, expenses = BigDecimal.ZERO;
    long count = 0;
    Map<String, BigDecimal> spending = new HashMap<>();
    for (var total : totals) {
      count += total.count();
      if (total.direction().equals("INCOME")) income = income.add(total.amount());
      else {
        expenses = expenses.add(total.amount());
        spending.put(total.category(), total.amount());
      }
    }
    Map<String, BigDecimal> budgets = new HashMap<>();
    repository.budgets(owner, period).forEach(b -> budgets.put(b.category(), b.amount()));
    List<CategoryView> categories = new ArrayList<>();
    List<Insight> insights = new ArrayList<>();
    for (String category : EXPENSE) {
      BigDecimal spent = spending.getOrDefault(category, BigDecimal.ZERO),
          budget = budgets.get(category);
      String percent =
          budget == null
              ? null
              : spent
                  .multiply(new BigDecimal("100"))
                  .divide(budget, 0, RoundingMode.HALF_UP)
                  .toPlainString();
      categories.add(
          new CategoryView(
              category,
              decimal(spent),
              budget == null ? null : decimal(budget),
              budget == null ? null : decimal(budget.subtract(spent)),
              percent));
      if (budget != null && spent.compareTo(budget) > 0)
        insights.add(
            new Insight(
                "warning",
                label(category) + " is over budget",
                "You recorded "
                    + decimal(spent.subtract(budget))
                    + " THB more than its limit. Review entries and adjust this category"
                    + " deliberately.",
                "Recorded category spending compared with your saved monthly budget."));
      else if (budget != null && spent.compareTo(budget.multiply(new BigDecimal("0.80"))) >= 0)
        insights.add(
            new Insight(
                "info",
                label(category) + " is near its limit",
                decimal(budget.subtract(spent)) + " THB remains in this category.",
                "At least 80% of this saved category budget is used."));
    }
    BigDecimal net = income.subtract(expenses);
    String savingsRate =
        income.signum() > 0
            ? net.multiply(new BigDecimal("100"))
                .divide(income, 1, RoundingMode.HALF_UP)
                .toPlainString()
            : null;
    if (count == 0)
      insights.add(
          new Insight(
              "info",
              "Start with your first entry",
              "Record income and everyday spending to see useful totals and budget suggestions.",
              "No tracker entries exist for this month."));
    else if (income.signum() == 0)
      insights.add(
          new Insight(
              "info",
              "Income is missing",
              "Add your recorded income before interpreting your savings rate. Bank top-ups are not"
                  + " automatically counted as income.",
              "This month has no recorded income."));
    else if (net.signum() < 0)
      insights.add(
          new Insight(
              "warning",
              "Recorded spending exceeds income",
              "There is a "
                  + decimal(net.abs())
                  + " THB shortfall. Review the largest expense categories and essential"
                  + " commitments first.",
              "Recorded income minus recorded expenses is negative."));
    else
      insights.add(
          new Insight(
              "positive",
              "A recorded surplus to plan with",
              "You have "
                  + decimal(net)
                  + " THB after recorded expenses. Consider assigning a manageable portion to a"
                  + " savings goal.",
              "Recorded income minus recorded expenses; unrecorded obligations are not included."));
    BigDecimal discretionary =
        spending
            .getOrDefault("SHOPPING", BigDecimal.ZERO)
            .add(spending.getOrDefault("ENTERTAINMENT", BigDecimal.ZERO));
    if (discretionary.signum() > 0)
      insights.add(
          new Insight(
              "info",
              "Try a small spending experiment",
              "A 10% reduction in recorded shopping and entertainment would free about "
                  + decimal(discretionary.multiply(new BigDecimal("0.10")))
                  + " THB. Choose a target that fits your needs.",
              "Illustrative calculation, not a prediction or personalized financial advice."));
    BigDecimal previous =
        repository.totals(owner, period.minusMonths(1)).stream()
            .filter(t -> t.direction().equals("EXPENSE"))
            .map(FinanceRepository.Total::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Summary(
        period.toString(),
        "THB",
        decimal(income),
        decimal(expenses),
        decimal(net),
        savingsRate,
        count,
        decimal(previous),
        categories,
        insights);
  }

  private static String decimal(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String label(String value) {
    return value.substring(0, 1) + value.substring(1).toLowerCase(java.util.Locale.ROOT);
  }
}
