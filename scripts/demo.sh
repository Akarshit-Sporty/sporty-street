#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mode=full
reset=false
for arg in "$@"; do
  case "$arg" in
    --core) mode=core ;;
    --reset) reset=true ;;
    *) echo 'Usage: scripts/demo.sh [--core] [--reset]'; exit 2 ;;
  esac
done
# The host only needs Docker. The app image includes curl and jq for this demo.
docker compose exec -T -e DEMO_MODE="$mode" -e RESET_DEMO="$reset" app sh -s <<'DEMO'
set -eu
api=http://localhost:8080/api/v1
api_get() { curl -fsS -H 'X-Dev-User: alice' "$api/$1"; }
api_post() {
  body="${2:-}"
  [ -n "$body" ] || body='{}'
  curl -fsS -X POST -H 'X-Dev-User: alice' -H 'Content-Type: application/json' "$api/$1" -d "$body"
}
if [ "$RESET_DEMO" = true ]; then api_post dev/reset '{}' >/dev/null; fi
api_get me | jq '{username,countryId}'
api_get rules | jq '{captainMultiplier,homeHero,twist,rewards}'
round=$(api_get "rounds?status=OPEN&demoMode=false" | jq -r '.items[] | select(.status=="OPEN" or .status=="LIVE") | .id' | head -1)
[ -n "$round" ] || { echo 'No open/live demo round; run with --reset for a new round.'; exit 1; }
if ! team=$(api_get "rounds/$round/my-team" 2>/dev/null); then
  pick=$(api_post "rounds/$round/auto-pick" '{"preferHomeHero":true}')
  lineup=$(printf '%s' "$pick" | jq '{starterPlayerIds:[.starters[].id],benchPlayerId:.bench.id,captainPlayerId}')
  team=$(curl -fsS -X POST -H 'X-Dev-User: alice' -H 'Content-Type: application/json' -H "Idempotency-Key: demo-team-$round" "$api/rounds/$round/teams" -d "$lineup")
fi
team_id=$(printf '%s' "$team" | jq -r '.id')
printf '%s' "$team" | jq '{id,score,homeHeroPlayerId,players:[.starters[].player.name]}'
api_post "dev/rounds/$round/start" '{}' >/dev/null
stream=$(mktemp)
trap 'kill "$stream_pid" 2>/dev/null || true; rm -f "$stream"' EXIT
curl -fsSN --max-time 300 -H 'X-Dev-User: alice' "$api/rounds/$round/stream" >"$stream" &
stream_pid=$!
api_post dev/simulator/start "{\"roundId\":\"$round\"}" >/dev/null
attempt=0
while [ "$attempt" -lt 25 ]; do
  attempt=$((attempt+1)); team=$(api_get "rounds/$round/my-team")
  score=$(printf '%s' "$team" | jq '.score')
  if [ "$score" -gt 0 ] && grep -q 'ROUND_SNAPSHOT' "$stream"; then break; fi
  sleep 1
done
[ "$score" -gt 0 ] && grep -q 'ROUND_SNAPSHOT' "$stream" || { echo 'No score/SSE received'; exit 1; }
if [ "$DEMO_MODE" = core ]; then
  printf '%s' "$team" | jq '{score,rank,stateVersion}'
  echo 'CORE DEMO PASSED. Simulator continues for about three minutes.'
  exit 0
fi
if [ "$(printf '%s' "$team" | jq '.subsRemaining')" -gt 0 ]; then
  request=$(printf '%s' "$team" | jq '. as $t | ($t.bench.player.countryId) as $country | {outgoingPlayerId: (([$t.starters[] | select(.player.countryId==$country)][0] // $t.starters[0]).player.id),incomingPlayerId:$t.bench.player.id,expectedVersion:$t.version}')
  pending=$(curl -fsS -X POST -H 'X-Dev-User: alice' -H 'Content-Type: application/json' -H "Idempotency-Key: demo-sub-$round" "$api/teams/$team_id/substitutions" -d "$request")
  printf '%s' "$pending" | jq -e '.pendingSubstitution != null' >/dev/null
  printf '%s' "$pending" | jq '{pendingSubstitution,subsRemaining}'
fi
attempt=0
while [ "$attempt" -lt 135 ]; do
  status=$(api_get dev/simulator)
  if [ "$(printf '%s' "$status" | jq -r '.lastError')" != null ]; then printf '%s\n' "$status"; exit 1; fi
  if [ "$(printf '%s' "$status" | jq -r '.completed')" = true ]; then break; fi
  if [ $((attempt % 15)) -eq 0 ]; then printf '%s' "$status" | jq '{currentMatchMinute,running}'; fi
  attempt=$((attempt+1));sleep 2
done
[ "$(printf '%s' "$status" | jq -r '.completed')" = true ] || { echo 'Simulator timed out'; exit 1; }
team=$(api_get "rounds/$round/my-team")
printf '%s' "$team" | jq -e '.pendingSubstitution == null and .subsUsed == 1' >/dev/null
breakdown=$(api_get "teams/$team_id/score-breakdown")
printf '%s' "$breakdown" | jq -e 'any(.timeline[]; .component=="LATE_GOAL_BONUS")' >/dev/null
api_post "dev/rounds/$round/settle" '{}' | jq .
result=$(api_get "rounds/$round/result")
printf '%s' "$result" | jq '{finalScore,finalRank,participationRp,winnerRp,totalAwardedRp}'
printf '%s' "$result" | jq -e '.participationRp==50 and .totalAwardedRp>=50' >/dev/null
before=$(api_get rewards/wallet | jq '.balanceRp')
gift=$(api_get rewards/catalogue | jq -r '[.items[] | select(.canClaim and .gift.category=="SPORTY_MERCH")] | min_by(.gift.priceRp) | .gift.id')
[ "$gift" != null ] || { echo 'Not enough RP for Merch; play more rounds or explicitly reset for seeded history.'; exit 1; }
purchase=$(curl -fsS -X POST -H 'X-Dev-User: alice' -H "Idempotency-Key: demo-gift-$round" "$api/gifts/$gift/purchases")
retry=$(curl -fsS -X POST -H 'X-Dev-User: alice' -H "Idempotency-Key: demo-gift-$round" "$api/gifts/$gift/purchases")
[ "$purchase" = "$retry" ] || { echo 'Purchase retry changed response'; exit 1; }
price=$(printf '%s' "$purchase" | jq '.purchase.priceRp')
after=$(api_get rewards/wallet | jq '.balanceRp')
[ "$after" -eq $((before-price)) ] || { echo 'Unexpected wallet debit'; exit 1; }
printf '%s' "$purchase" | jq .
api_get me/purchases | jq '{total,items}'
api_get rewards/history | jq '{total,items}'
echo 'FULL DEMO PASSED: team → live score/SSE → delayed substitution → late goals → settlement → RP → gift → safe retry.'
DEMO
