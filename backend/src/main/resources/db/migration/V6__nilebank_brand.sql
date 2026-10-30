-- Preserve already-applied migration checksums and existing simulated balances.
UPDATE schema_metadata SET application='NileBank simulated money' WHERE id=1;
