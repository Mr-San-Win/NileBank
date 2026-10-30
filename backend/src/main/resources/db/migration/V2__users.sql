CREATE TABLE app_users (
 id uuid PRIMARY KEY,
 full_name varchar(100) NOT NULL CHECK (length(trim(full_name)) > 0),
 email varchar(254) NOT NULL UNIQUE CHECK (email = lower(trim(email))),
 password_hash varchar(100) NOT NULL,
 created_at timestamptz NOT NULL
);
