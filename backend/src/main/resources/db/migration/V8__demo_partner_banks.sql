ALTER TABLE banking_transactions DROP CONSTRAINT banking_transactions_type_check;
ALTER TABLE banking_transactions ADD CONSTRAINT banking_transactions_type_check CHECK(type IN ('DEPOSIT','WITHDRAWAL','TRANSFER','EXCHANGE','BANK_IN','BANK_OUT'));
ALTER TABLE banking_transactions DROP CONSTRAINT transaction_shape;
ALTER TABLE banking_transactions ADD CONSTRAINT transaction_shape CHECK(
 (type IN ('DEPOSIT','BANK_IN') AND source_id IS NULL AND destination_id IS NOT NULL) OR
 (type IN ('WITHDRAWAL','EXCHANGE','BANK_OUT') AND source_id IS NOT NULL AND destination_id IS NULL) OR
 (type='TRANSFER' AND source_id IS NOT NULL AND destination_id IS NOT NULL AND source_id<>destination_id));
CREATE TABLE partner_connections (
 id uuid PRIMARY KEY,
 account_id uuid NOT NULL REFERENCES bank_accounts(id),
 bank_code varchar(20) NOT NULL CHECK(bank_code IN ('HARBOR','ORCHID')),
 status varchar(12) NOT NULL CHECK(status IN ('CONNECTED','DISCONNECTED')),
 balance numeric(19,2) NOT NULL CHECK(balance>=0),
 created_at timestamptz NOT NULL,
 UNIQUE(account_id,bank_code)
);
CREATE TABLE partner_movements (
 id uuid PRIMARY KEY,
 account_id uuid NOT NULL REFERENCES bank_accounts(id),
 connection_id uuid NOT NULL REFERENCES partner_connections(id),
 direction varchar(3) NOT NULL CHECK(direction IN ('IN','OUT')),
 amount numeric(19,2) NOT NULL CHECK(amount>0 AND amount<=1000000),
 client_key varchar(100) NOT NULL,
 transaction_id uuid NOT NULL UNIQUE REFERENCES banking_transactions(id),
 account_balance_after numeric(19,2) NOT NULL CHECK(account_balance_after>=0),
 partner_balance_after numeric(19,2) NOT NULL CHECK(partner_balance_after>=0),
 created_at timestamptz NOT NULL,
 UNIQUE(account_id,client_key)
);
