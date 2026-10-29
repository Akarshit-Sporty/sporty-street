CREATE TABLE reward_wallet (user_id uuid PRIMARY KEY REFERENCES app_user(id), balance integer NOT NULL DEFAULT 0 CHECK(balance>=0));
CREATE TABLE gift (
 id uuid PRIMARY KEY, name varchar(100) NOT NULL, description text NOT NULL, category varchar(30) NOT NULL,
 image_url text, price_rp integer NOT NULL CHECK(price_rp>0), active boolean NOT NULL DEFAULT true
);
CREATE TABLE gift_purchase (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id), gift_id uuid NOT NULL REFERENCES gift(id),
 gift_name varchar(100) NOT NULL, price_rp integer NOT NULL CHECK(price_rp>0), voucher_code varchar(100) NOT NULL UNIQUE,
 created_at timestamptz NOT NULL
);
CREATE INDEX purchase_user ON gift_purchase(user_id,created_at DESC);
CREATE TABLE reward_ledger (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id), delta integer NOT NULL, reason varchar(30) NOT NULL,
 round_id uuid REFERENCES app_round(id), purchase_id uuid UNIQUE REFERENCES gift_purchase(id), created_at timestamptz NOT NULL,
 UNIQUE(user_id,round_id,reason),
 CHECK((reason IN ('PARTICIPATION','WINNER') AND delta>0 AND round_id IS NOT NULL AND purchase_id IS NULL)
    OR (reason='PURCHASE' AND delta<0 AND round_id IS NULL AND purchase_id IS NOT NULL))
);
CREATE INDEX ledger_user ON reward_ledger(user_id,created_at DESC);
