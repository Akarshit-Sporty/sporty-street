CREATE TABLE country (
    id uuid PRIMARY KEY,
    code varchar(3) NOT NULL UNIQUE,
    name varchar(100) NOT NULL
);
CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    username varchar(50) NOT NULL UNIQUE,
    display_name varchar(100) NOT NULL,
    country_id uuid REFERENCES country(id)
);
CREATE TABLE idempotency_record (
    user_id uuid NOT NULL REFERENCES app_user(id),
    key varchar(200) NOT NULL,
    method_path varchar(300) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response_status integer NOT NULL,
    response_body text NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, key, method_path)
);
