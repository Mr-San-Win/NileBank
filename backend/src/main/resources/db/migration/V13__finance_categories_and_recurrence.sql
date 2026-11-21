CREATE TABLE finance_categories (
 owner_id uuid NOT NULL REFERENCES app_users(id),
 code varchar(24) NOT NULL,
 direction varchar(7) NOT NULL CHECK(direction IN ('INCOME','EXPENSE')),
 label varchar(60) NOT NULL CHECK(length(trim(label))>0),
 archived boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(owner_id,code), UNIQUE(owner_id,code,direction)
);
CREATE UNIQUE INDEX uq_active_category_name ON finance_categories(owner_id,direction,lower(label)) WHERE NOT archived;
CREATE FUNCTION default_finance_categories() RETURNS TABLE(code text,direction text,label text)
LANGUAGE sql IMMUTABLE AS $$ VALUES
 ('SALARY','INCOME','Salary'),('FREELANCE','INCOME','Freelance'),('OTHER_INCOME','INCOME','Other income'),
 ('FOOD','EXPENSE','Food'),('HOUSING','EXPENSE','Housing'),('TRANSPORT','EXPENSE','Transport'),
 ('SHOPPING','EXPENSE','Shopping'),('HEALTH','EXPENSE','Health'),('EDUCATION','EXPENSE','Education'),
 ('ENTERTAINMENT','EXPENSE','Entertainment'),('OTHER','EXPENSE','Other') $$;
INSERT INTO finance_categories(owner_id,code,direction,label)
 SELECT u.id,c.code,c.direction,c.label FROM app_users u CROSS JOIN default_finance_categories() c;
CREATE FUNCTION seed_user_finance_categories() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 INSERT INTO finance_categories(owner_id,code,direction,label)
 SELECT NEW.id,c.code,c.direction,c.label FROM default_finance_categories() c;
 RETURN NEW;
END; $$;
CREATE TRIGGER user_finance_categories AFTER INSERT ON app_users FOR EACH ROW EXECUTE FUNCTION seed_user_finance_categories();
ALTER TABLE finance_entries DROP CONSTRAINT finance_entries_category_check;
ALTER TABLE finance_entries DROP CONSTRAINT finance_entries_check;
ALTER TABLE finance_entries ADD CONSTRAINT entry_owned_category FOREIGN KEY(owner_id,category,direction) REFERENCES finance_categories(owner_id,code,direction);
ALTER TABLE finance_budgets DROP CONSTRAINT finance_budgets_category_check;
ALTER TABLE finance_budgets ADD COLUMN direction varchar(7) NOT NULL DEFAULT 'EXPENSE' CHECK(direction='EXPENSE');
ALTER TABLE finance_budgets ADD CONSTRAINT budget_owned_category FOREIGN KEY(owner_id,category,direction) REFERENCES finance_categories(owner_id,code,direction);
CREATE TABLE finance_recurring_rules (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES app_users(id),
 direction varchar(7) NOT NULL CHECK(direction IN ('INCOME','EXPENSE')),
 category varchar(24) NOT NULL,
 amount numeric(19,2) NOT NULL CHECK(amount>0 AND amount<=1000000),
 description varchar(120) NOT NULL DEFAULT '',
 frequency varchar(7) NOT NULL CHECK(frequency IN ('DAILY','WEEKLY','MONTHLY')),
 start_date date NOT NULL, end_date date CHECK(end_date>=start_date),
 next_occurrence date NOT NULL CHECK(next_occurrence>=start_date),
 status varchar(10) NOT NULL CHECK(status IN ('ACTIVE','PAUSED','CANCELLED','COMPLETED')),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 UNIQUE(owner_id,id),
 FOREIGN KEY(owner_id,category,direction) REFERENCES finance_categories(owner_id,code,direction)
);
CREATE INDEX ix_recurring_due ON finance_recurring_rules(next_occurrence,id) WHERE status='ACTIVE';
CREATE INDEX ix_recurring_owner ON finance_recurring_rules(owner_id,created_at,id);
CREATE TABLE finance_occurrences (
 rule_id uuid NOT NULL REFERENCES finance_recurring_rules(id),
 occurred_on date NOT NULL,
 PRIMARY KEY(rule_id,occurred_on)
);
ALTER TABLE finance_entries ADD COLUMN recurring_rule_id uuid;
ALTER TABLE finance_entries ADD COLUMN scheduled_for date;
ALTER TABLE finance_entries ADD CONSTRAINT recurring_entry_shape CHECK((recurring_rule_id IS NULL AND scheduled_for IS NULL) OR (recurring_rule_id IS NOT NULL AND scheduled_for IS NOT NULL));
ALTER TABLE finance_entries ADD CONSTRAINT recurring_entry_owner FOREIGN KEY(owner_id,recurring_rule_id) REFERENCES finance_recurring_rules(owner_id,id);
ALTER TABLE finance_entries ADD CONSTRAINT recurring_entry_occurrence FOREIGN KEY(recurring_rule_id,scheduled_for) REFERENCES finance_occurrences(rule_id,occurred_on);
ALTER TABLE finance_entries ADD CONSTRAINT uq_recurring_entry UNIQUE(recurring_rule_id,scheduled_for);
