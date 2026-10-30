CREATE TABLE finance_entries (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES app_users(id),
 direction varchar(7) NOT NULL CHECK(direction IN ('INCOME','EXPENSE')),
 category varchar(24) NOT NULL CHECK(category IN ('SALARY','FREELANCE','OTHER_INCOME','FOOD','HOUSING','TRANSPORT','SHOPPING','HEALTH','EDUCATION','ENTERTAINMENT','OTHER')),
 amount numeric(19,2) NOT NULL CHECK(amount>0 AND amount<=1000000),
 description varchar(120) NOT NULL DEFAULT '',
 occurred_on date NOT NULL, created_at timestamptz NOT NULL,
 CHECK((direction='INCOME' AND category IN ('SALARY','FREELANCE','OTHER_INCOME')) OR
       (direction='EXPENSE' AND category IN ('FOOD','HOUSING','TRANSPORT','SHOPPING','HEALTH','EDUCATION','ENTERTAINMENT','OTHER')))
);
CREATE INDEX ix_finance_owner_date ON finance_entries(owner_id,occurred_on DESC,created_at DESC,id);
CREATE TABLE finance_budgets (
 owner_id uuid NOT NULL REFERENCES app_users(id), month_start date NOT NULL CHECK(extract(day from month_start)=1),
 category varchar(24) NOT NULL CHECK(category IN ('FOOD','HOUSING','TRANSPORT','SHOPPING','HEALTH','EDUCATION','ENTERTAINMENT','OTHER')),
 amount numeric(19,2) NOT NULL CHECK(amount>0 AND amount<=1000000),
 PRIMARY KEY(owner_id,month_start,category)
);
CREATE TABLE savings_goals (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES app_users(id),
 name varchar(80) NOT NULL CHECK(length(trim(name))>0),
 target numeric(19,2) NOT NULL CHECK(target>0 AND target<=1000000),
 saved numeric(19,2) NOT NULL DEFAULT 0 CHECK(saved>=0 AND saved<=target),
 created_at timestamptz NOT NULL
);
CREATE INDEX ix_savings_owner ON savings_goals(owner_id,created_at);
