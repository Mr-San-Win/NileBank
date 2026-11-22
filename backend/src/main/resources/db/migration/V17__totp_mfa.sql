CREATE TABLE mfa_settings (
 user_id uuid PRIMARY KEY REFERENCES app_users(id),
 encrypted_secret varchar(200) NOT NULL,
 key_id varchar(32) NOT NULL,
 status varchar(8) NOT NULL CHECK(status IN ('PENDING','ACTIVE')),
 enrollment_expires_at timestamptz NOT NULL,
 last_counter bigint CHECK(last_counter>=0),
 failed_attempts integer NOT NULL DEFAULT 0 CHECK(failed_attempts BETWEEN 0 AND 10),
 failure_window timestamptz NOT NULL DEFAULT current_timestamp,
 created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE TABLE mfa_recovery_codes (
 user_id uuid NOT NULL REFERENCES mfa_settings(user_id) ON DELETE CASCADE,
 code_hash char(64) NOT NULL CHECK(code_hash ~ '^[0-9a-f]{64}$'),
 consumed_at timestamptz,
 PRIMARY KEY(user_id,code_hash)
);
