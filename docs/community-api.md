# Social screens: frontend integration

The backend now provides the data for Leagues, Rivalries, Banter and Back a manager. These are
**read-only simulated datasets** (`dataMode: "SIMULATED"`), enabled in the `local` profile. They do
not depend on frontend hardcoded arrays. Authentication is the same as the rest of the API:
`X-Dev-User: alice`, or the cookie from `/api/v1/dev/login`.

No database reset is needed. Flyway adds `app_user.avatar_url`; startup fills missing avatars
for the eight existing users without changing countries, scores, balances or saved lineups.
Existing non-null custom avatar URLs are retained.

## API calls

All paths below are relative to `http://localhost:8080/api/v1`.

| Screen | Request | Data to render |
|---|---|---|
| Current user | `GET /me` | `avatarUrl` alongside existing identity fields |
| My Leagues | `GET /leagues?scope=MINE` | `items[]`: name, imageUrl, avatarUrl, memberPreview, myRank, memberCount |
| Creator discovery | `GET /leagues?scope=DISCOVER` | Creator leagues the current user has not joined |
| All leagues | `GET /leagues?scope=ALL` | All six leagues; useful for initial frontend integration |
| League detail | `GET /leagues/{id}` | `league` card and complete `members[]` for this small fixture |
| Table tab | `GET /leagues/{id}/table` | `items[]`: profile, rank, score, wins |
| Rivalries tab | `GET /leagues/{id}/rivalries` | `items[]` duels and `challenges[]` invitations |
| Banter tab | `GET /leagues/{id}/banter` | `messages.items[]` and `quickMessages[]` |
| Back a manager | `GET /backing/managers?roundId={roundId}&filter=ALL` | `items[]` manager cards, plus actual round name/status |

League lists, tables, Banter messages and managers support `page=0&size=20` (size 1–100).
Unknown IDs return 404, invalid filters or pagination return 400. League list scope is
`MINE`, `DISCOVER` or `ALL`; manager filter is `ALL`, `FRIENDS`, `TOP_FORM` or `CREATORS`.

```sh
curl -H 'X-Dev-User: alice' 'http://localhost:8080/api/v1/leagues?scope=ALL'
# Use a league id from items[]:
curl -H 'X-Dev-User: alice' 'http://localhost:8080/api/v1/leagues/LEAGUE_ID/rivalries'
curl -H 'X-Dev-User: alice' 'http://localhost:8080/api/v1/leagues/LEAGUE_ID/banter'
# Use a round id from /rounds (or /rounds?status=SETTLED for a completed round):
curl -H 'X-Dev-User: alice' 'http://localhost:8080/api/v1/backing/managers?roundId=ROUND_ID&filter=FRIENDS'
```

## Avatars and league artwork

- `/me`, login responses and round leaderboard/SSE entries now include `avatarUrl`.
- Community `profile`, `author`, `you`, `opponent`, `from`, `to` and `creator` objects include
  `avatarUrl`. Member previews are `Person` objects, not initials.
- A league's `imageUrl` is its cover/card artwork. Its `avatarUrl` is the creator's portrait,
  or the league artwork for a friends/work league.
- All URLs are backend-origin-relative, for example `/media/avatars/alice.jpg` or
  `/media/leagues/lagos-ballers.jpg`. GET/HEAD requires no authentication.
- Resolve against the backend **origin**, not `/api/v1`, or proxy `/media` to the backend.

```jsx
const backendOrigin = "http://localhost:8080";
<img
  src={new URL(profile.avatarUrl, backendOrigin).href}
  alt={profile.displayName}
  style={{ width: 40, height: 40, borderRadius: "50%", objectFit: "cover" }}
/>
```

Twelve default avatar photos (cats, a dog and actors) and six league images are bundled into
Docker. Actor photos are decorative profile avatars, not claims that the actors own accounts or
endorse the app. Commons attribution/licences are in `/media/avatars/credits.json` and
`/media/leagues/credits.json`. Optional refresh: `python3 scripts/fetch-community-images.py`.
No runtime download or extra image service is involved.

## Contents and semantics

- Alice sees Lagos Ballers, Office Five · Opennet, Mr. Pundit League and Kay Football League
  under MINE; Samba Tactics and Abi Ballon under DISCOVER.
- Lagos Ballers has 12 members, four Alice rivalries, two incoming challenges and one outgoing
  challenge. Office Five has three Alice rivalries. Other viewers only receive their own duels;
  score and W/L form reverse when the viewer is the other participant.
- Banter has 26 messages across six leagues; Lagos Ballers has eight distinct authors/messages.
  Twelve quick-message presets and four reaction counts per message are supplied.
- Alice has 14 manager candidates: seven FRIENDS, nine TOP_FORM, four CREATORS. A manager can
  belong to multiple filters. The viewer is excluded from their own candidate list.
- Manager `profile.id` identifies a community profile, not a team. Some profiles link to the
  existing demo users; other profiles exist only in this fixture and are not login accounts.
- `squadPreview` is an auto-picked illustrative five using the round's real player catalogue;
  it never reads or reveals another user's saved lineup. `captainPlayerId` belongs to it.
- Targets are `TOP_HALF`, `TOP_25_PERCENT`, `TOP_10_PERCENT`. Multipliers, average points, fans,
  past form and head-to-head scores are fictional preview data, not calculated betting odds.
- League standings, friendship relationships, historical results, reactions and messages come
  from `src/main/resources/seed/community.json`. They do not change when the match simulator
  scores goals or settles a round. Message times are anchored to application startup.

## Actions remain outside this update

`readOnly: true` is included in league-detail, rivalries, Banter and manager responses.
The API supplies content; it does not implement join/create league, send/accept/rematch challenge,
post/react to Banter, place/settle a backing, or a fan-points wallet. Disable or explicitly label
those actions as frontend previews. There are no write endpoints for these datasets and no RP
or fan-point debits. The existing real round-scoring and RP/gift purchase APIs are unchanged.

The frontend code is not in this repository. The screen changes take effect once the frontend
switches its hardcoded datasets to these endpoints and renders the supplied image URLs.


## Later profile and friends update

See [Profile / Play / Rewards](profile-play-rewards-api.md) for the database-backed profile,
10 login accounts (8 avatars), 40-game history, rolling schedule, real friends/invitations and
new reward categories. The friends/managers/league statistics described above remain read-only
preview fixtures; `/me/friends` and `/me/invitations` are the persisted invitation workflow.
