-- Only automatically managed practice rounds have this marker. Existing rounds keep their rewards.
CREATE TABLE demo_round (
 round_id uuid PRIMARY KEY REFERENCES app_round(id),
 ends_at timestamptz NOT NULL,
 event_cursor integer NOT NULL DEFAULT 0 CHECK(event_cursor>=0)
);
CREATE INDEX round_retention ON app_round(status,settled_at);
CREATE INDEX idempotency_response_round ON idempotency_record ((response_body::jsonb ->> 'roundId'));
