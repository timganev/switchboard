#!/usr/bin/env bash
# Checks the stack from compose.yml end to end: every service, API keys, both APIs, the guardian's verdicts, the ledger and the trace.
set -u

GATEWAY=${GATEWAY:-http://localhost:8080}
LEDGER=${LEDGER:-http://localhost:8082}
TRACE=${TRACE:-http://localhost:8083}
OPENSEARCH=${OPENSEARCH:-http://localhost:9200}
ADMIN_TOKEN=${SWITCHBOARD_AUTH_ADMIN_TOKEN:-local-demo-admin-token-change-me-0123}
TEAM="smoke-$(date +%s)"
EGN=7501020018
failures=0

check() {
  if [ "$2" = true ]; then
    echo "ok   $1"
  else
    echo "FAIL $1"
    failures=$((failures + 1))
  fi
}

# Retries a condition for up to $1 seconds, since services start and events arrive in their own time.
eventually() {
  local seconds=$1
  shift
  for _ in $(seq 1 "$seconds"); do
    if eval "$@"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

for port in 8080 8081 8082 8083 8084 8090; do
  eventually 120 "curl -sf localhost:$port/actuator/health | grep -q '\"UP\"'" && up=true || up=false
  check "service on port $port is up" $up
done

issued=$(curl -s "$GATEWAY/admin/keys" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d "{\"team\": \"$TEAM\"}")
KEY=$(echo "$issued" | grep -o '"key":"sb-[^"]*"' | cut -d'"' -f4)
KEY_ID=$(echo "$issued" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
check "the admin issued a key for the team" "$([ -n "$KEY" ] && echo true || echo false)"

status=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/v1/chat/completions" -H 'Content-Type: application/json' -H "X-Switchboard-Team: $TEAM" \
  -d '{"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}')
check "a request without a key is refused" "$([ "$status" = 401 ] && echo true || echo false)"

headers=$(curl -s -o /dev/null -D - "$GATEWAY/v1/chat/completions" -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" \
  -d "{\"model\": \"qwen36\", \"messages\": [{\"role\": \"user\", \"content\": \"Risks for EGN $EGN?\"}]}")
echo "$headers" | grep -q '^HTTP/1.1 200' && answered=true || answered=false
echo "$headers" | grep -qi '^X-Switchboard-Route: surrogate; reason=EGN' && surrogated=true || surrogated=false
check "OpenAI format: an EGN is answered" $answered
check "OpenAI format: the EGN went out as a surrogate" $surrogated

status=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/v1/messages" -H 'Content-Type: application/json' -H "x-api-key: $KEY" \
  -d '{"model": "qwen36", "max_tokens": 50, "messages": [{"role": "user", "content": "Hi"}]}')
check "Anthropic format: a plain prompt is answered with the key in x-api-key" "$([ "$status" = 200 ] && echo true || echo false)"

status=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/v1/chat/completions" -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" \
  -d '{"model": "qwen36", "messages": [{"role": "user", "content": "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA1234567890abcdef\n-----END RSA PRIVATE KEY-----"}]}')
check "a private key is blocked" "$([ "$status" = 403 ] && echo true || echo false)"

eventually 60 "curl -sf '$LEDGER/v1/budgets/$TEAM' | grep -q '\"team\":\"$TEAM\"'" && budget=true || budget=false
check "the ledger keeps a budget for the team" $budget

eventually 60 "[ \$(curl -sf '$TRACE/v1/calls?team=$TEAM' | grep -o '\"verdict\":\"[A-Z]*\"' | wc -l) -ge 3 ]" && traced=true || traced=false
check "the trace merged all three calls with their verdicts" $traced

hits=$(curl -s "$OPENSEARCH/_search?q=$EGN" | grep -o '"total":{"value":[0-9]*' | grep -o '[0-9]*$')
check "the real EGN is nowhere in OpenSearch" "$([ "${hits:-1}" = 0 ] && echo true || echo false)"

status=$(curl -s -o /dev/null -w '%{http_code}' "$TRACE/")
check "the live view is served" "$([ "$status" = 200 ] && echo true || echo false)"

curl -s -o /dev/null -X DELETE "$GATEWAY/admin/keys/$KEY_ID" -H "Authorization: Bearer $ADMIN_TOKEN"
status=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/v1/chat/completions" -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" \
  -d '{"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}')
check "a revoked key is refused on the next request" "$([ "$status" = 401 ] && echo true || echo false)"

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
