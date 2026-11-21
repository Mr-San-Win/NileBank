ALTER TABLE app_users ADD COLUMN credential_version bigint NOT NULL DEFAULT 0 CHECK(credential_version>=0);
