-- API history is append-only, enforced even against accidental SQL updates.
CREATE FUNCTION reject_transaction_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Banking transaction history is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER banking_transactions_immutable
BEFORE UPDATE OR DELETE ON banking_transactions
FOR EACH ROW EXECUTE FUNCTION reject_transaction_mutation();
