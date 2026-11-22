ALTER TABLE app_users ADD COLUMN email_verified_at timestamptz;
CREATE TABLE email_verification_tokens (
 token_hash char(64) PRIMARY KEY CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 user_id uuid NOT NULL REFERENCES app_users(id),
 email_snapshot varchar(254) NOT NULL,
 credential_version bigint NOT NULL CHECK(credential_version>=0),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 expires_at timestamptz NOT NULL CHECK(expires_at>created_at),
 delivered_at timestamptz,
 consumed_at timestamptz
);
CREATE INDEX ix_email_verify_user_time ON email_verification_tokens(user_id,created_at DESC);
CREATE INDEX ix_email_verify_expiry ON email_verification_tokens(expires_at);
