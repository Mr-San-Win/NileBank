ALTER TABLE banking_transactions ADD COLUMN idempotency_key varchar(100);
ALTER TABLE banking_transactions ADD CONSTRAINT uq_transfer_key UNIQUE(source_id,idempotency_key);
ALTER TABLE banking_transactions ADD CONSTRAINT transfer_key_shape CHECK(
 (type='TRANSFER' AND idempotency_key IS NOT NULL AND length(idempotency_key)>=8) OR
 (type<>'TRANSFER' AND idempotency_key IS NULL)
);
