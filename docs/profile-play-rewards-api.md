# Profile, Play, friends and rewards — frontend handoff

All API requests use `X-Dev-User: alice` (or the existing dev login cookie).
The backend and DB implement these features; the frontend repository is separate.
Keep `/me` for session/identity. Use the following endpoints for the enriched screens.

## Profile and overall leaderboard

- `GET /api/v1/me/profile`: `user`, `bio`, `joinedAt`, `stats`, `progress`,
  `leaderboard`, `badges`, `topHomeHero`, `roundHistory` (first 10 entries).
- `GET /api/v1/me/round-history?page=0&size=20`: full history including current entries.
- `GET /api/v1/leaderboard?page=0&size=20`: cumulative scores from **settled rounds**.

`stats`: roundsPlayed, averagePoints, bestFinish, wins, substitutions, smartSubs,
streakDays. `smartSubs` counts substitutions whose incoming player earned positive points.
Streak is consecutive UTC dates with completed games, ending today or yesterday.
`progress`: xp, level, xpInLevel, xpForNextLevel, title. XP is 100 per completed round
plus its fantasy points; each 1,000 XP adds a level. `topHomeHero` includes player image
and accumulated Home Hero bonus. A profile with no completed games has zero statistics,
null joinedAt/bestFinish/topHomeHero and locked achievement badges.

History entries: roundId, name, status, startsAt, score, rank (null before settlement),
earnedRp. Use the existing round result endpoint to show the full score breakdown.
Overall rankings use shared ranks for ties and stable user-ID ordering.
These points unlock SportyBet Rewards; they are never spent or converted to RP.

Ten actual local login accounts exist: alice, bob, carol, dave, eve, frank, grace,
henry, ivy, jack. Eight have avatars (80%); ivy and jack have `avatarUrl: null`.
Render initials for null URLs. This proportion refers to login accounts; read-only
community preview personas remain a separate fixture catalogue.

## Play

```sh
curl -H 'X-Dev-User: alice' 'http://localhost:8080/api/v1/rounds?period=TODAY&timeZone=Europe/Minsk'
curl -H 'X-Dev-User: alice' 'http://localhost:8080/api/v1/rounds?period=TOMORROW&timeZone=Europe/Minsk'
curl -H 'X-Dev-User: alice' 'http://localhost:8080/api/v1/rounds?period=THIS_WEEK&format=QUICK&timeZone=Europe/Minsk'
```

- `period`: optional TODAY, TOMORROW, THIS_WEEK. THIS_WEEK means the next seven
  calendar dates, including today, rather than ending at Sunday.
- `timeZone`: IANA zone, default UTC; used for calendar date boundaries.
- `format`: optional QUICK, MATCHDAY, SPECIAL.
- `status`: existing OPEN, LIVE, SETTLED; default still OPEN/LIVE.
- Standard page/size, max 100; invalid filter/time zone returns 400.
- Each round now includes `format`, `matchCount`, `participantCount`, `simulated`;
  existing `fixtures` contain real club names and kickoff times. No fabricated audience counts.
- `participantCount` counts stored teams belonging to real login accounts. To show
  their names/avatars/scores use `/rounds/{id}/leaderboard`.

The local seed adds 40 historical rounds with scored events/results and RP ledgers;
11 upcoming rounds over seven UTC dates (3 today, 3 tomorrow, 1 on each following day),
plus the original rounds. Champions Night is promoted from DRAFT only once. Each new
upcoming round has 8 actual saved teams; Alice and Dave can enter themselves. QUICK
rounds have one Real Madrid–Barcelona fixture; other formats have three fixtures.
All match schedules/results are simulated; the football roster is the fixed 2024/25 catalogue.

The schedule extends on startup and hourly at :10 UTC without moving existing games,
recreating teams, replaying historical rewards or resetting purchases. OPEN games become
LIVE at their deadlines as before; scoring/settlement still use the existing simulator
or explicit development controls. No simulation is started automatically.

## Friends and round invitations

Alice has three persisted friends: Bob, Carol and Dave.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/v1/me/friends` | Paginated friends with id, username, displayName, avatarUrl, leaderboardPoints |
| GET | `/api/v1/me/invitations` | Paginated incoming and outgoing invitations visible only to their participants |
| POST | `/api/v1/friends/{friendId}/invitations` | Body `{"roundId":"..."}`, `Idempotency-Key` required; creates a saved invitation |
| POST | `/api/v1/invitations/{id}/accept` | Recipient only; joins the round with an auto-picked team if they have no team |
| POST | `/api/v1/invitations/{id}/decline` | Recipient only; saves the rejection |

Invitations have id, sender, recipient, roundId, roundName, status, createdAt, respondedAt.
Compare sender.id to `/me.id` for direction. Status is PENDING, ACCEPTED, DECLINED or
EXPIRED (derived when a pending invitation's round is no longer open).
No email, SMS or push delivery: the recipient sees it through `/me/invitations`.
No extra RP is awarded for invitations. Recruiter/Team Player badge unlocks after an accepted invitation.

Only friends can be invited; only OPEN rounds before deadline accept invites/entry.
Duplicates for the same sender/recipient/round return the original invitation, even with
another key. Repeat accept/decline of the same outcome succeeds without another team.
Conflicting responses return INVITATION_CLOSED; unauthorized responses return 403.
A team created by accepting can be edited normally before the round deadline.

Example (substitute IDs from the two GET requests):

```sh
curl -H 'X-Dev-User: alice' http://localhost:8080/api/v1/me/friends
curl -X POST -H 'X-Dev-User: alice' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: invite-dave-1' \
  -d '{"roundId":"ROUND_ID"}' http://localhost:8080/api/v1/friends/DAVE_ID/invitations
curl -H 'X-Dev-User: dave' http://localhost:8080/api/v1/me/invitations
curl -X POST -H 'X-Dev-User: dave' http://localhost:8080/api/v1/invitations/INVITATION_ID/accept
```

## Two reward categories

**Use `GET /api/v1/rewards/catalogue` for the Rewards screen.** It returns wallet,
leaderboard, two named categories and personalized offer `items`:

```text
item.gift: id, name, description, category, imageUrl, priceRp, requiredPoints, onePerUser
item: unlocked, affordable, claimed, canClaim, pointsNeeded, rpNeeded, lockReason
```

`canClaim` controls the button; `lockReason` is null, LEADERBOARD_POINTS,
INSUFFICIENT_RP or ALREADY_CLAIMED. RP and leaderboard progress must be rendered separately.
`/gifts` remains a plain paginated catalogue and supports `category=SPORTY_MERCH` or
`category=SPORTYBET_REWARDS`; it does not include personalized offer state.

| Category | Gift | Cost / eligibility |
|---|---|---|
| Sporty Merch (`SPORTY_MERCH`) | Sporty Red Cap | 3,000 RP |
| Sporty Merch | Sporty Red T-shirt | 4,500 RP |
| Sporty Merch | Sporty Red Hoodie | 6,000 RP |
| SportyBet Rewards (`SPORTYBET_REWARDS`) | Referral Reward | Free, unlocked for every account |
| SportyBet Rewards | Free Bet | 500 lifetime leaderboard points |
| SportyBet Rewards | Cashback | 3,000 lifetime leaderboard points |
| SportyBet Rewards | Lucky Wheel | 5,000 lifetime leaderboard points |

The thresholds are **points, not top-10/top-100 ranks**: ten test accounts would otherwise
all qualify immediately. Leaderboard points are an eligibility threshold, not another wallet.
SportyBet gifts cost 0 RP, are claimable once per account and never deduct leaderboard points.
Referral Reward has no referral-count condition in this version, as requested.

Merch prices target roughly 30/45/60 days at 100 RP per day (one round daily, ~50% wins).
Actual speed depends on frequency/results: participation is 50 RP, winner total is 150 RP.
The new history gives Alice 4,000 RP (plus any previous balance) and enough points for Free Bet,
while Cashback/Lucky Wheel remain locked. Thus she can afford the cap. No balance is restored
on restart or after a purchase; existing balances/results/purchases are preserved.

All claims and Merch purchases use the existing endpoint:

```sh
curl -X POST -H 'X-Dev-User: alice' -H 'Idempotency-Key: claim-1' \
  http://localhost:8080/api/v1/gifts/GIFT_ID/purchases
```

Returns 201 `{purchase, balanceRp}`. Persisted vouchers appear in `/me/purchases`.
Repeated identical key replays the response. A different key for an already-claimed one-time
reward returns 409 REWARD_ALREADY_CLAIMED. Ineligible points return 422 REWARD_LOCKED;
insufficient RP returns 422 INSUFFICIENT_RP. Free claims create a purchase but no RP ledger
entry; paid purchases debit wallet and ledger atomically. The same wallet lock serializes
concurrent paid/free claims. Old gifts are retired, but their purchase snapshots remain intact.

SportyBet images are the supplied references. Codes are local entitlements only: there is
no real betting, cashback payout, partner account or fulfilment integration.

## Images and deployment

All seven reward images are bundled at `/media/rewards/**`; GET/HEAD need no auth.
Merch images are unmodified product references from Beechfield/Gildan, with source records
in `/media/rewards/credits.json`. `scripts/fetch-reward-images.py` refreshes Merch photos manually;
it is not run by Compose or the application. No runtime external requests are needed.

Use `new URL(imageUrl, API_ORIGIN).href` or proxy `/media` to the backend.
Existing API paths remain available; the frontend must call the new profile/catalogue/friends
endpoints and use the Play filters to show the new information.

`docker compose up -d --build --wait` applies migration V6 and adds the sample history and
schedule without a reset. `sporty.showcase.enabled=false` disables only the rich history/schedule
seed for focused integration tests; the default Compose keeps it enabled. Explicit `/dev/reset`
recreates the rich dataset. No ZIP archive is produced for this update.

## Continuous practice rounds

See the [README integration guide](../README.md#continuous-live-demo-frontend-integration).
`GET /api/v1/demo/session` returns current LIVE and next OPEN rounds. Their `demoMode=true`
separates them from ordinary seeded calendar rounds (`simulated=true` alone does not).
Optional `GET /rounds?demoMode=false` keeps the calendar limited to ordinary games.
Practice fantasy scores and results work, but award zero RP and do not affect this guide's
profile statistics, XP, history, badges, permanent leaderboard or reward eligibility.
Switch per-round SSE subscriptions when the session's current ID changes; refresh the session
every five seconds. Practice results/invitations expire 24 hours after settlement.
