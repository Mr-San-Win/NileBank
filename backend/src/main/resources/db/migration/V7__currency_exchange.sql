ALTER TABLE banking_transactions DROP CONSTRAINT banking_transactions_type_check;
ALTER TABLE banking_transactions ADD CONSTRAINT banking_transactions_type_check CHECK(type IN ('DEPOSIT','WITHDRAWAL','TRANSFER','EXCHANGE'));
ALTER TABLE banking_transactions DROP CONSTRAINT transaction_shape;
ALTER TABLE banking_transactions ADD CONSTRAINT transaction_shape CHECK (
 (type='DEPOSIT' AND source_id IS NULL AND destination_id IS NOT NULL) OR
 (type IN ('WITHDRAWAL','EXCHANGE') AND source_id IS NOT NULL AND destination_id IS NULL) OR
 (type='TRANSFER' AND source_id IS NOT NULL AND destination_id IS NOT NULL AND source_id<>destination_id));
ALTER TABLE banking_transactions ADD COLUMN currency varchar(3) NOT NULL DEFAULT 'THB' CHECK(currency IN ('THB','USD','EUR'));
ALTER TABLE banking_transactions ADD COLUMN counter_currency varchar(3) CHECK(counter_currency IN ('THB','USD','EUR'));
ALTER TABLE banking_transactions ADD COLUMN counter_amount numeric(19,2) CHECK(counter_amount>0);
ALTER TABLE banking_transactions ADD COLUMN description varchar(180);
ALTER TABLE banking_transactions ADD CONSTRAINT exchange_shape CHECK(
 (type='EXCHANGE' AND counter_currency IS NOT NULL AND counter_amount IS NOT NULL AND currency<>counter_currency) OR
 (type<>'EXCHANGE' AND counter_currency IS NULL AND counter_amount IS NULL AND currency='THB'));
CREATE TABLE currency_wallets (
 account_id uuid NOT NULL REFERENCES bank_accounts(id),
 currency varchar(3) NOT NULL CHECK(currency IN ('USD','EUR')),
 balance numeric(19,2) NOT NULL DEFAULT 0 CHECK(balance>=0),
 PRIMARY KEY(account_id,currency)
);
CREATE TABLE exchange_quotes (
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL REFERENCES app_users(id),
 account_id uuid NOT NULL REFERENCES bank_accounts(id),
 from_currency varchar(3) NOT NULL CHECK(from_currency IN ('THB','USD','EUR')),
 to_currency varchar(3) NOT NULL CHECK(to_currency IN ('THB','USD','EUR')),
 amount numeric(19,2) NOT NULL CHECK(amount>0 AND amount<=1000000),
 received_amount numeric(19,2) NOT NULL CHECK(received_amount>0),
 rate numeric(19,8) NOT NULL CHECK(rate>0),
 expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL,
 executed_transaction_id uuid UNIQUE REFERENCES banking_transactions(id),
 CHECK(from_currency<>to_currency)
);
CREATE INDEX ix_quotes_owner_time ON exchange_quotes(owner_id,created_at DESC);

ALTER TABLE exchange_quotes ADD COLUMN provider varchar(80) NOT NULL;
ALTER TABLE exchange_quotes ADD COLUMN rate_date date NOT NULL;
ALTER TABLE exchange_quotes ADD COLUMN fetched_at timestamptz NOT NULL;
