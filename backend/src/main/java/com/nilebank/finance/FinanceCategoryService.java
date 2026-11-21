package com.nilebank.finance;

import com.nilebank.common.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceCategoryService {
  private final JdbcTemplate jdbc;

  public FinanceCategoryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Category(String code, String direction, String label, boolean archived) {}

  @Transactional(readOnly = true)
  public List<Category> list(UUID owner) {
    return jdbc.query(
        "select code,direction,label,archived from finance_categories where owner_id=? order by"
            + " direction,label,code",
        (r, n) -> new Category(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4)),
        owner);
  }

  private String name(String value) {
    if (value == null || value.isBlank() || value.strip().length() > 60)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Category name must contain 1-60 characters");
    return value.strip();
  }

  public void require(UUID owner, String code, String direction, boolean allowArchived) {
    var rows =
        jdbc.query(
            "select direction,archived from finance_categories where owner_id=? and code=? for"
                + " share",
            (r, n) -> new Category(code, r.getString(1), "", r.getBoolean(2)),
            owner,
            code);
    if (rows.isEmpty()
        || !rows.getFirst().direction().equals(direction)
        || (!allowArchived && rows.getFirst().archived()))
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Choose an available category matching income or expense");
  }

  @Transactional
  public Category create(UUID owner, String direction, String label) {
    if (!List.of("INCOME", "EXPENSE").contains(direction == null ? "" : direction))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Choose income or expense");
    label = name(label);
    String code = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    jdbc.update(
        "insert into finance_categories(owner_id,code,direction,label) values(?,?,?,?)",
        owner,
        code,
        direction,
        label);
    return new Category(code, direction, label, false);
  }

  @Transactional
  public Category rename(UUID owner, String code, String label) {
    label = name(label);
    if (jdbc.update(
            "update finance_categories set label=? where owner_id=? and code=? and not archived",
            label,
            owner,
            code)
        != 1) throw new ApiException(HttpStatus.NOT_FOUND, "Active category not found");
    return list(owner).stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
  }

  @Transactional
  public void archive(UUID owner, String code) {
    // NO KEY UPDATE permits generator FK checks while serializing new manual assignment checks.
    var rows =
        jdbc.query(
            "select code from finance_categories where owner_id=? and code=? for no key update",
            (r, n) -> r.getString(1),
            owner,
            code);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Category not found");
    jdbc.update(
        "update finance_categories set archived=true where owner_id=? and code=?", owner, code);
    jdbc.update(
        "update finance_recurring_rules set status='PAUSED' where owner_id=? and category=? and"
            + " status='ACTIVE'",
        owner,
        code);
  }
}
