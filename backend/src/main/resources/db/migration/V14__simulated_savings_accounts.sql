CREATE TABLE savings_accounts (
 id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES bank_accounts(id),
 name varchar(80) NOT NULL CHECK(length(trim(name))>0),
 currency varchar(3) NOT NULL DEFAULT 'THB' CHECK(currency='THB'),
 balance numeric(19,2) NOT NULL DEFAULT 0 CHECK(balance>=0 AND balance<=99999999999999999.99),
 status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN')),
 target numeric(19,2) CHECK(target>0 AND target<=1000000),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 UNIQUE(id,account_id)
);
CREATE UNIQUE INDEX uq_savings_name ON savings_accounts(account_id,lower(name));
CREATE INDEX ix_savings_parent ON savings_accounts(account_id,created_at,id);
ALTER TABLE banking_transactions DROP CONSTRAINT banking_transactions_type_check;
ALTER TABLE banking_transactions ADD CONSTRAINT banking_transactions_type_check CHECK(type IN ('DEPOSIT','WITHDRAWAL','TRANSFER','EXCHANGE','BANK_IN','BANK_OUT','SAVINGS_IN','SAVINGS_OUT'));
ALTER TABLE banking_transactions DROP CONSTRAINT transaction_shape;
ALTER TABLE banking_transactions ADD CONSTRAINT transaction_shape CHECK(
 (type IN ('DEPOSIT','BANK_IN','SAVINGS_OUT') AND source_id IS NULL AND destination_id IS NOT NULL) OR
 (type IN ('WITHDRAWAL','EXCHANGE','BANK_OUT','SAVINGS_IN') AND source_id IS NOT NULL AND destination_id IS NULL) OR
 (type='TRANSFER' AND source_id IS NOT NULL AND destination_id IS NOT NULL AND source_id<>destination_id));
CREATE TABLE savings_movements (
 id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES bank_accounts(id),
 savings_id uuid NOT NULL,
 direction varchar(3) NOT NULL CHECK(direction IN ('IN','OUT')),
 amount numeric(19,2) NOT NULL CHECK(amount>0 AND amount<=1000000),
 client_key varchar(100) NOT NULL CHECK(length(client_key)>=8),
 transaction_id uuid NOT NULL UNIQUE REFERENCES banking_transactions(id),
 main_balance_after numeric(19,2) NOT NULL CHECK(main_balance_after>=0),
 savings_balance_after numeric(19,2) NOT NULL CHECK(savings_balance_after>=0),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 FOREIGN KEY(savings_id,account_id) REFERENCES savings_accounts(id,account_id),
 UNIQUE(account_id,client_key)
);
CREATE INDEX ix_savings_movement_time ON savings_movements(savings_id,created_at DESC,id);
CREATE TRIGGER savings_movements_immutable BEFORE UPDATE OR DELETE ON savings_movements FOR EACH ROW EXECUTE FUNCTION reject_transaction_mutation();
