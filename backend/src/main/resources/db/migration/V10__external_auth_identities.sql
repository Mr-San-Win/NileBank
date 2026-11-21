-- Existing password users and financial references remain unchanged.
ALTER TABLE app_users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE auth_identities (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES app_users(id),
 provider varchar(32) NOT NULL CHECK (length(trim(provider)) > 0),
 issuer varchar(512) COLLATE "C" NOT NULL CHECK (length(trim(issuer)) > 0),
 provider_subject varchar(255) COLLATE "C" NOT NULL CHECK (length(trim(provider_subject)) > 0),
 created_at timestamptz NOT NULL,
 CONSTRAINT uq_auth_identity_subject UNIQUE (issuer, provider_subject),
 CONSTRAINT uq_auth_identity_user_provider UNIQUE (user_id, provider)
);
