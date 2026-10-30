CREATE TABLE bank_accounts (
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL UNIQUE REFERENCES app_users(id),
 account_number varchar(32) NOT NULL UNIQUE,
 currency varchar(3) NOT NULL DEFAULT 'THB' CHECK(currency='THB'),
 balance numeric(19,2) NOT NULL DEFAULT 0 CHECK(balance>=0 AND balance<=99999999999999999.99),
 status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN')),
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL
);
CREATE TABLE banking_transactions (
 id uuid PRIMARY KEY,
 type varchar(12) NOT NULL CHECK(type IN ('DEPOSIT','WITHDRAWAL','TRANSFER')),
 status varchar(12) NOT NULL CHECK(status='SUCCESS'),
 amount numeric(19,2) NOT NULL CHECK(amount>0 AND amount<=1000000),
 source_id uuid REFERENCES bank_accounts(id),
 destination_id uuid REFERENCES bank_accounts(id),
 reference varchar(40) NOT NULL UNIQUE,
 created_at timestamptz NOT NULL,
 CONSTRAINT transaction_shape CHECK (
 (type='DEPOSIT' AND source_id IS NULL AND destination_id IS NOT NULL) OR
 (type='WITHDRAWAL' AND source_id IS NOT NULL AND destination_id IS NULL) OR
 (type='TRANSFER' AND source_id IS NOT NULL AND destination_id IS NOT NULL AND source_id<>destination_id))
);
CREATE INDEX ix_transactions_source_time ON banking_transactions(source_id,created_at DESC,id);
CREATE INDEX ix_transactions_destination_time ON banking_transactions(destination_id,created_at DESC,id);
