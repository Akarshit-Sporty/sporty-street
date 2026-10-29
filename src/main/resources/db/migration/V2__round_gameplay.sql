CREATE TABLE club (id uuid PRIMARY KEY, name varchar(100) NOT NULL, country_id uuid NOT NULL REFERENCES country(id));
CREATE TABLE player (
 id uuid PRIMARY KEY, name varchar(100) NOT NULL, position varchar(3) NOT NULL CHECK(position IN ('GK','DEF','MID','FWD')),
 country_id uuid NOT NULL REFERENCES country(id), club_id uuid NOT NULL REFERENCES club(id), image_url text
);
CREATE TABLE app_round (
 id uuid PRIMARY KEY, name varchar(100) NOT NULL, status varchar(20) NOT NULL CHECK(status IN ('DRAFT','OPEN','LIVE','SETTLED')),
 entry_deadline timestamptz NOT NULL, sub_window_opens_at timestamptz NOT NULL, sub_window_closes_at timestamptz NOT NULL,
 rules jsonb NOT NULL, state_version bigint NOT NULL DEFAULT 0, settled_at timestamptz,
 CHECK(sub_window_closes_at > sub_window_opens_at)
);
CREATE TABLE fixture (
 id uuid PRIMARY KEY, round_id uuid NOT NULL REFERENCES app_round(id), provider varchar(50) NOT NULL,
 provider_fixture_id varchar(100) NOT NULL, home_club_id uuid NOT NULL REFERENCES club(id), away_club_id uuid NOT NULL REFERENCES club(id),
 kickoff_at timestamptz NOT NULL, UNIQUE(provider, provider_fixture_id)
);
CREATE INDEX fixture_round ON fixture(round_id);
CREATE TABLE round_player (
 round_id uuid NOT NULL REFERENCES app_round(id), player_id uuid NOT NULL REFERENCES player(id),
 fixture_id uuid NOT NULL REFERENCES fixture(id), eligible boolean NOT NULL DEFAULT true, PRIMARY KEY(round_id,player_id)
);
CREATE TABLE team (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id), round_id uuid NOT NULL REFERENCES app_round(id),
 captain_player_id uuid NOT NULL REFERENCES player(id), home_country_id uuid REFERENCES country(id),
 subs_used integer NOT NULL DEFAULT 0 CHECK(subs_used IN (0,1)), version bigint NOT NULL DEFAULT 0,
 UNIQUE(user_id,round_id)
);
CREATE TABLE team_selection (
 id uuid PRIMARY KEY, team_id uuid NOT NULL REFERENCES team(id), player_id uuid NOT NULL REFERENCES player(id),
 role varchar(10) NOT NULL CHECK(role IN ('STARTER','BENCH')), effective_from timestamptz NOT NULL, effective_to timestamptz,
 CHECK(effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX selection_team ON team_selection(team_id);
CREATE TABLE team_score (team_id uuid PRIMARY KEY REFERENCES team(id), round_id uuid NOT NULL REFERENCES app_round(id), total integer NOT NULL DEFAULT 0);
CREATE INDEX score_round ON team_score(round_id,total DESC);
CREATE TABLE match_event (
 id uuid PRIMARY KEY, provider varchar(50) NOT NULL, provider_event_id varchar(200) NOT NULL, revision integer NOT NULL CHECK(revision>0),
 fixture_id uuid NOT NULL REFERENCES fixture(id), player_id uuid NOT NULL REFERENCES player(id), event_type varchar(30) NOT NULL,
 match_minute integer NOT NULL CHECK(match_minute>=0), occurred_at timestamptz NOT NULL, voided boolean NOT NULL,
 request_hash varchar(64) NOT NULL, received_at timestamptz NOT NULL, acceptance_response text NOT NULL,
 UNIQUE(provider,provider_event_id,revision)
);
CREATE INDEX event_fixture ON match_event(fixture_id);
CREATE TABLE score_contribution (
 id uuid PRIMARY KEY, round_id uuid NOT NULL REFERENCES app_round(id), team_id uuid NOT NULL REFERENCES team(id),
 player_id uuid NOT NULL REFERENCES player(id), match_event_id uuid NOT NULL REFERENCES match_event(id),
 component varchar(30) NOT NULL, points integer NOT NULL, explanation text NOT NULL,
 UNIQUE(team_id,match_event_id,component)
);
CREATE INDEX contribution_team ON score_contribution(team_id);
