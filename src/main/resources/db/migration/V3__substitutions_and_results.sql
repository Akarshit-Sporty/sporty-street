CREATE TABLE team_substitution (
 team_id uuid PRIMARY KEY REFERENCES team(id), outgoing_player_id uuid NOT NULL REFERENCES player(id),
 incoming_player_id uuid NOT NULL REFERENCES player(id), accepted_at timestamptz NOT NULL, effective_at timestamptz NOT NULL,
 CHECK(outgoing_player_id <> incoming_player_id), CHECK(effective_at > accepted_at)
);
CREATE TABLE round_result (
 team_id uuid PRIMARY KEY REFERENCES team(id), round_id uuid NOT NULL REFERENCES app_round(id),
 final_score integer NOT NULL, final_rank bigint NOT NULL, participation_rp integer NOT NULL DEFAULT 0,
 winner_rp integer NOT NULL DEFAULT 0, settled_at timestamptz NOT NULL
);
CREATE INDEX result_round ON round_result(round_id);
