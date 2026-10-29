CREATE TABLE seed_marker (name text PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE round_presentation (
 round_id uuid PRIMARY KEY REFERENCES app_round(id),
 format varchar(12) NOT NULL CHECK(format IN ('QUICK','MATCHDAY','SPECIAL')),
 simulated boolean NOT NULL DEFAULT true
);
CREATE TABLE friendship (
 user_id uuid NOT NULL REFERENCES app_user(id), friend_id uuid NOT NULL REFERENCES app_user(id),
 PRIMARY KEY(user_id,friend_id), CHECK(user_id<>friend_id)
);
CREATE TABLE round_invitation (
 id uuid PRIMARY KEY, sender_id uuid NOT NULL REFERENCES app_user(id),
 recipient_id uuid NOT NULL REFERENCES app_user(id), round_id uuid NOT NULL REFERENCES app_round(id),
 status varchar(12) NOT NULL CHECK(status IN ('PENDING','ACCEPTED','DECLINED')),
 created_at timestamptz NOT NULL, responded_at timestamptz,
 UNIQUE(sender_id,recipient_id,round_id), CHECK(sender_id<>recipient_id)
);
ALTER TABLE gift DROP CONSTRAINT gift_price_rp_check;
ALTER TABLE gift ADD CONSTRAINT gift_price_rp_check CHECK(price_rp>=0);
ALTER TABLE gift ADD COLUMN required_points integer NOT NULL DEFAULT 0 CHECK(required_points>=0);
ALTER TABLE gift ADD COLUMN one_per_user boolean NOT NULL DEFAULT false;
ALTER TABLE gift_purchase DROP CONSTRAINT gift_purchase_price_rp_check;
ALTER TABLE gift_purchase ADD CONSTRAINT gift_purchase_price_rp_check CHECK(price_rp>=0);
