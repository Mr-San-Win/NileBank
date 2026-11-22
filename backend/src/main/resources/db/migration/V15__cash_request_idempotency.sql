CREATE TABLE cash_requests (
 account_id uuid NOT NULL REFERENCES bank_accounts(id),
 operation varchar(12) NOT NULL CHECK(operation IN ('DEPOSIT','WITHDRAWAL')),
 client_key varchar(100) NOT NULL CHECK(length(client_key)>=8),
 amount numeric(19,2) NOT NULL CHECK(amount>0 AND amount<=1000000),
 transaction_id uuid NOT NULL UNIQUE REFERENCES banking_transactions(id),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(account_id,operation,client_key)
);
CREATE TRIGGER cash_requests_immutable BEFORE UPDATE OR DELETE ON cash_requests FOR EACH ROW EXECUTE FUNCTION reject_transaction_mutation();
