# Switchboard

An AI gateway in Java that keeps sensitive data close to home and measures whether it succeeds.

Every company now wants its teams to use the strongest cloud models without sending out data that
must not leave. Switchboard is the single door all LLM traffic goes through. For every request it
decides where the request may go, sends it there, and records why.

> **Status:** the gateway, the guardian, the ledger and the trace work: both APIs, streaming, the
> in-flight board, surrogates and restore, events, budgets reconciled with the bill, masked traces,
> the live view, API keys per team and a playground. The whole stack starts from compose, is checked
> end to end and can be pulled as images ([Run the demo](#run-the-demo)). Leak Lab measures leakage,
> re-identification, answer quality, cost and latency per strategy, with the decision model slot and
> without; hardening is next (see [Roadmap](#roadmap)).

## Principles

1. **One door.** Clients (applications, AI agents, coding assistants) talk only to Switchboard, in
   the OpenAI or Anthropic API format. Behind the door are cloud and local models; the client does not
   need to know which one answered.
2. **The data decides the route, not convenience.** The more sensitive a request, the closer to home
   it stays. When in doubt, it stays local. If the local model is down, the request fails with a clear
   error and never quietly falls back to the cloud.
3. **Deterministic first, models only where needed.** Checksummed rules catch most sensitive values
   in milliseconds. A decision model is optional and only advises.
4. **A model can only tighten, never loosen.** On the privacy path a decision model may raise the
   risk level, never lower it. A cloud-hosted decision model is not allowed on that path at all.
5. **Measure, don't promise.** Every decision is recorded with its reason, and a built-in red team
   keeps attacking the protection. The result is a number on the dashboard.

## Architecture

```
                         ┌──────────────────────────┐
  client / AI agent ────►│         gateway          │◄──── system-tests (end to end)
  (OpenAI or Anthropic   │                          │◄──── leak-lab (attacks through the door)
   API format)           │  doorman                 │
                         │  + dispatcher            │
                         │  + decision model slot   │
                         │    (optional)            │
                         └──┬──────────┬─────────┬──┘
              REST          │          │         │  HTTP
      /inspect, /restore    │          │         ▼
                            ▼          │   ┌────────────────────────┐
                 ┌────────────────┐    │   │ models                 │
                 │   guardian     │    │   │  local: vLLM, Ollama   │
                 │ guard, judge   │    │   │  cloud: Claude, ...    │
                 └────────────────┘    │   └────────────────────────┘
                                       ▼
                              ┌──────────────────┐
                              │ Redis            │
                              │ in-flight board  │
                              │ per model        │
                              │ instance         │
                              └──────────────────┘

 ═════════════════════════════════ Kafka ═════════════════════════════════
   llm-call-completed       ◄── gateway
   privacy-decision-made    ◄── guardian
   budget-status-changed    ◄── ledger      (compacted, read back by gateway)
   eval-run-completed       ◄── leak-lab
 ═══════╤════════════════════════════╤════════════════════════════════════
        ▼                            ▼
 ┌──────────────┐            ┌──────────────┐
 │    trace     │            │    ledger    │
 │  black box   │            │  accountant  │
 └──────┬───────┘            └──────┬───────┘
        ▼                           ▼
   OpenSearch                   Postgres
   masked traces                prices, spend, budgets
        ▲
        │ REST: what exactly left the boundary
 ┌──────┴───────┐
 │   leak-lab   │──► Postgres (datasets, runs, results)
 │   red team   │
 └──────────────┘
```

### Who does what

| Service | Role | Owns data |
|---|---|---|
| `gateway` | **Doorman and dispatcher.** Authenticates teams, translates the OpenAI and Anthropic formats into one request, asks the guardian for a verdict, picks the least busy healthy model instance inside the allowed set, streams the answer back. Holds the decision model slot for task difficulty | Redis: the in-flight board (shared by all gateway replicas). Postgres: hashes of the teams' API keys |
| `guardian` | **Guardian and judge.** Finds sensitive data in every part of the request, has the final word on the route, swaps values for surrogates and restores them, guards agents against the lethal trifecta. Holds the decision model slot for unclear sensitivity, which accepts only a local model. A separate process so the security boundary can be tested and attacked on its own | Nothing at rest |
| `ledger` | **Accountant.** Prices every call, aggregates spend per team, publishes the budget state | Postgres: the system of record for money |
| `trace` | **Black box.** What happened and why; feeds the live view | OpenSearch: masked traces and decisions, never raw sensitive values |
| `leak-lab` | **Red team.** Attacks the protection through the gateway and measures leakage, quality, cost and latency for every strategy | Postgres: datasets, runs, results |
| `mock-llm` | Fake OpenAI-compatible provider for tests and offline demos; it echoes the prompt, so a demo shows what reached the model | No |
| `console` | **Playground.** One prompt's way through Switchboard: what the guardian lets out, and what comes back | No |
| `commons` | Event contracts and shared types | — |
| `system-tests` | End-to-end tests against a deployed system | — |

## The hot path

Example: an agent sends a contract with a Bulgarian national ID (EGN) and an IBAN and asks for a risk summary.

```
client ──► gateway: authenticate, translate into the internal format
              │
              ├──► guardian /inspect ── verdict: SURROGATE, reason: EGN, IBAN
              │    (decision model slot: optional, local only, can only tighten)
              │
              ├── dispatcher inside the gateway (no network hop):
              │     budget ok? → difficulty (decision model slot, optional)
              │     → least busy healthy instance in the allowed set (Redis)
              │
              ├──► cloud model (sees only surrogates)
              │
              ├──► guardian /restore ── real values back, the answer is scanned
              │
client ◄──────┘    header: X-Switchboard-Route: surrogate; reason=IBAN,EGN
              │
              └──► Kafka: llm-call-completed  (asynchronous, off the client's path)
```

The guardian scans every part of the request: system prompt, messages, tool arguments and tool
results, and tool definitions. Patterns find candidates and check digits confirm them: EGN and LNCH
(Bulgarian personal and foreigner IDs), IBAN (mod 97), payment cards (Luhn), email addresses, Bulgarian
phone numbers, API keys by their known prefixes, JWTs and private keys. Each type maps to a route, and
the strictest route among the findings wins. It returns one of five verdicts:

| Verdict | What happens |
|---|---|
| `CLOUD` | Straight to a cloud model |
| `SURROGATE` | Realistic fake values go out, the real ones are restored in the answer |
| `SKETCH` | The cloud model drafts on de-identified text, a local model fills in the real details |
| `LOCAL` | Local model only; fails closed if none is available |
| `BLOCK` | Rejected, e.g. a private key in the prompt |

### A vault that does not exist

Surrogates are deterministic: `HMAC(conversation key, real value)` becomes a fake value of the same
type that still passes its check digits, so the model reasons about it as it would about the real one.
Chat APIs resend the whole history on every turn, so the guardian sees the real values again and turns
them into the same surrogates. To restore an answer, the gateway sends the original request along and
the guardian rebuilds the mapping, so nothing is stored, not even between the two calls. For the same
reason sensitivity sticks to a conversation without being remembered: the value is still in the
history. The key is derived from the conversation's first message, so a provider cannot link the same
value across conversations. A surrogate that would collide with a real value or with another
surrogate is derived again.

If the guardian does not answer, the gateway sends nothing (503). A `LOCAL` request only ever goes to
instances marked local; when they all fail, the client gets an error, never a quiet trip to the cloud.
A streamed surrogate answer is restored as it arrives: the guardian hands the gateway the
substitutions for this one request, and the gateway holds back only a short tail that could still be
the start of a split surrogate. The response scan needs the whole answer, so only non-streamed answers
carry `X-Switchboard-Response-Findings`.

Models rewrite values: an EGN with dashes between the digits, an IBAN in lower case or without spaces,
a phone number with `0` instead of `+359`. Restore finds a surrogate written with spaces, dots or
dashes and in any letter case, and puts the real value back in the model's spacing.

### The decision model slot

Patterns find identifiers, not names or health data. The guardian can ask a local model about those,
after the detectors and only when the answer could still tighten the route (`CLOUD` or `SURROGATE`).
There is no cloud option: asking a cloud model whether a prompt is sensitive would already send it
out. The model answers in JSON that the server holds to a schema, with the words that prove each
category (`{"names": ["Anna Georgieva"], "health": ["follow-up visit"]}`), so it cannot reason in prose
before it answers. A claim whose words are not in the text is dropped, and a name must be
capitalised words, not a role like "the customer". Names alone are swapped for surrogates like the
detectors' values: each word for a first name or a surname of the same gender and alphabet, replaced
only as a whole word, so the cloud still gets the task (`Radka Petrova` goes out as `Maria Dimitrova`,
`Мария Иванова` as `Десислава Колева`). The policy decides as for any type, so `NAME: LOCAL` keeps named
prompts in. Since the names come from a model and cannot be rebuilt from the request, the gateway hands
the inspection's substitutions back when it asks for the restore. Health data keeps the request `LOCAL`
(or `BLOCK`), because there is no value to swap, and so does a timeout or an answer that is not an
answer. The trace records which model and prompt version took part and whether the model
tightened the verdict. The slot is off by default:

```yaml
switchboard.guardian.decision-model:
  type: local                     # none (default) or local; there is no cloud type
  url: http://localhost:11434
  model: qwen3.6:35b-a3b
  reasoning-effort: none
  timeout: 10s
  route: LOCAL                    # LOCAL or BLOCK
```

The first version asked for free text. Without thinking, `qwen3.6:35b-a3b` then called a town a name and
a job health data, and with a little thinking it was right at about 20 seconds a check. With the JSON
schema and no thinking it made the same decisions on the 20 Leak Lab prompts in about 1 second a check
(prompt version 2), which is what the run below measures.

### The in-flight board

Redis has exactly one job: a shared board of how many requests each model instance is working on
right now. A small Lua script reads the board, picks the least busy instance and increments its
count in one atomic step, so several gateway replicas never pile onto the same instance while another
is idle. Local models serve only a few requests at once, so this has a direct effect on latency.
Counts are leases with a TTL, so a crashed gateway cannot leave an instance marked busy forever. If
Redis is down, each replica falls back to counting its own requests; a circuit breaker
(resilience4j) then skips Redis for a while (`switchboard.board.redis-retry-after`, 10s), so requests
do not each wait for a Redis timeout.

### Lethal trifecta guard

An agent that has private data, reads untrusted content and can send data out can be tricked into
leaking it ([Simon Willison](https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/)). The gateway
sees the agent's tool list in every request and the guardian knows whether the conversation holds
sensitive data. When all three meet, the outbound tool is removed, an approval is required, or the
route becomes `LOCAL`.

## Events

| Topic | Published by | Consumed by |
|---|---|---|
| `llm-call-completed` | gateway | ledger, trace |
| `privacy-decision-made` | guardian | trace |
| `budget-status-changed` | ledger | gateway (compacted topic held in memory), trace |
| `eval-run-completed` | leak-lab | trace |
| `<consumer>-dlq` | every consumer | redrive endpoint |

The contracts live in [`commons`](commons/src/main/java/io/github/timganev/switchboard/commons).
Privacy events carry the type and count of what was found, never the value.

The gateway publishes one `llm-call-completed` for every call however it ends (answered, blocked,
refused, failed), keyed by team; the guardian publishes one `privacy-decision-made` for every verdict,
under the same call id (`X-Switchboard-Call-Id`). Events go out on a virtual thread, so a slow or
missing broker never delays or fails a request. With API keys on, the team comes from the caller's key;
without them, from the `X-Switchboard-Team` header (`default` when absent). The session comes from
`X-Switchboard-Session`.

### API keys

Each team gets keys like the ones OpenAI and Anthropic issue (`sb-` and 43 random characters), and
sends one as `Authorization: Bearer <key>` or as `x-api-key`, which is where Claude Code and the
Anthropic SDKs put it. The gateway then takes the team from the key and ignores the team header. A
missing, unknown or revoked key gets a 401 in the caller's format. An admin issues, lists and revokes
keys at `/admin/keys` with the admin token. Postgres keeps only a SHA-256 hash of each key and its
first ten characters for display; the key itself is in the answer that issues it and nowhere else. A
check is one read by the hash, and the last use is written at most once a minute per key, so a busy key
does not turn every request into a write. The guardian knows the `sb-` prefix, so a key pasted into a
prompt goes out as a surrogate. Keys are off by default (`switchboard.auth.type: none`) and on in the
compose stack:

```bash
curl localhost:8080/admin/keys -H 'Authorization: Bearer local-demo-admin-token-change-me-0123' \
  -H 'Content-Type: application/json' -d '{"team": "payments"}'     # {"id": ..., "key": "sb-...", ...}
curl localhost:8080/v1/chat/completions -H 'Authorization: Bearer sb-...' -H 'Content-Type: application/json' \
  -d '{"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}'
curl -X DELETE localhost:8080/admin/keys/<id> -H 'Authorization: Bearer local-demo-admin-token-change-me-0123'
```

**Why API keys and not JWT.** Coding agents already send a bearer key, so nothing changes for them. There is no
identity server to run, and a revoked key stops working on the next request. A JWT is checked without a lookup,
but it stays valid until it expires, so revoking it needs a deny list, which is a lookup again. A user login
would bring JWT through the Spring OAuth2 resource server, but the gateway serves teams and their agents, not
people. Good to discuss: why only the hash is stored (a leaked table gives no keys, and SHA-256 is enough
because the keys are random, unlike passwords), comparing in constant time, a recognisable prefix so the
guardian and secret scanners catch a leaked key, and rotating with two live keys per team.

### Budgets

The ledger prices every call into Postgres (schema by Liquibase). Local calls cost nothing; a cloud
call is priced with the price valid at the time (`model_price`, one row per model and start date,
with a fallback row `*` so a model without a price is never free). The call id is the primary key,
so a redelivered event is counted once. After every call the ledger publishes the team's status for
the month (`OK`, `WARN` from 80%, `BLOCKED` at the limit) to the compacted `budget-status-changed`
topic. Each gateway replica reads that topic from the beginning into memory, so no lookup sits on
the request path. A team over its budget may still use local models (`X-Switchboard-Budget: spent;
local only`); without a local instance it gets a 429 with the reason. The budget fails open while
the topic is unread, because it is a cost control; privacy stays fail-closed.

```bash
curl -X PUT localhost:8082/v1/budgets/payments -H 'Content-Type: application/json' -d '{"monthlyLimitUsd": 50}'
curl localhost:8082/v1/budgets/payments
```

The estimate is only as good as the price table. The provider's own bill comes days later, per day
and model rather than per call, so the ledger takes it in as lines (`POST /v1/bills`; a later import
of the same day and model replaces the earlier one) and shares each billed amount out by every team's
estimate for that day and model. A status carries both numbers: `spentEstimate`, and
`spentReconciled` (billed days at what was billed, the rest still estimated). The state follows
`spentReconciled`, because it is the best known spend. A billed amount with no estimate behind it is
reported in the comparison but charged to no team.

```bash
curl -X POST localhost:8082/v1/bills -H 'Content-Type: application/json' \
  -d '{"lines": [{"date": "2026-10-30", "model": "cloud-model", "costUsd": 18.40}]}'
# [{"date":"2026-10-30","model":"cloud-model","estimatedUsd":16.92,"billedUsd":18.40,"differenceUsd":1.48}]
```

### Traces

The trace service merges both events of a call into one OpenSearch document: route, instance,
tokens, latency, status, and the guardian's verdict with the types and counts it found. Nothing in it
is a sensitive value, because nothing in the events is.

```bash
curl 'localhost:8083/v1/calls?team=payments&limit=20'   # newest first
curl localhost:8083/v1/calls/<call id>
```

### Playground

The console at `localhost:8088` takes a prompt as a user would write it and shows three things side
by side: the prompt with the real values the guardian found, what left for the model with the
surrogates in their place, and the answer the caller got back with the real values restored. With
the fake model, which echoes the prompt, the round trip is easy to follow: the surrogates go out and
the real values come back. A private key shows the refusal, and a name shows the decision model when
it is on. The page is plain HTML, CSS and one JavaScript module, with no framework and no build step.
The console's server side asks the guardian what would leave and sends the prompt through the gateway
with its own API key, so the key never reaches the browser.

![The playground](docs/playground.png)

### Live view

The trace service also serves the console at `localhost:8083`: the latest 50 calls rendered on the
server (Thymeleaf), then each new call pushed over server-sent events as an HTML row that htmx puts at
the top of the table. A row is pushed once the call has finished and is replaced when the guardian's
decision arrives later, so it is complete whichever event came first. `?team=payments` narrows both
the table and the stream. No Node toolchain: the page is one template.

![The live view](docs/console.png)

### Dead letters

A record a consumer cannot process is retried twice and then parked on `<consumer>-dlq` (raw bytes if
it could not even be read), so one bad record never blocks a partition. Once the cause is fixed (say
OpenSearch is back), the ledger and the trace service each send what they parked through their own
`<consumer>-redrive` topic, not the shared one, so no other consumer sees the record twice. A record
that fails again goes through the same retries and is parked again with the new reason.

```bash
curl localhost:8083/v1/dead-letters                 # where each record came from and why it failed
curl -X POST localhost:8083/v1/dead-letters/redrive # {"records": 2}
```

## Leak Lab

A second model receives exactly what left the boundary and tries to recover what was hidden or to
re-identify the person from what is left (age, job and town are often enough). Every strategy, from
no protection through placeholders and surrogates to `LOCAL`, is scored on four numbers: leakage,
answer quality, cost and latency, and the trade-off is drawn as a chart from a reproducible run.

A made-up population (EGN and IBAN pass the real check digits; the
same seed always gives the same people) gives one prompt per person: a loan application, a complaint,
an HR note, a patient note. Every other prompt leaves the name out, so only the town, the job and the
EGN point to the person. Each prompt goes through the real guardian once, and each strategy decides
what leaves:

| Strategy | What leaves |
|---|---|
| `NONE` | the prompt as it is |
| `MASK` | what the guardian found, replaced by `***` |
| `PLACEHOLDER` | what the guardian found, replaced by `[EGN_1]`, `[PHONE_1]`… |
| `SURROGATE` | what the guardian really sends: realistic fake values |
| `SURROGATE_MODEL` | the same with the [decision model slot](#the-decision-model-slot) on: names as surrogates too, nothing when the model finds health data |
| `LOCAL` | nothing |

Two numbers per strategy: the share of real values that left, and how often an attacker model names
the right person when it gets only what left and ten candidates who look most like that person (a
blind guess is right 10% of the time). The attacker is any OpenAI-compatible model, by default the
local `qwen3.6:35b-a3b`.

Then the user's side. Each strategy's prompt is answered (by the cloud model, or by the local one
when nothing may leave), and the answer is turned back into what the user would see: placeholders and
surrogates are replaced by the real values, masks stay masks. A trusted judge model sees the original
request and grades all the answers side by side, in a shuffled order, from 1 to 5. Cost prices only
the tokens that went to the cloud; latency includes the guardian where it is on the path. In this run
one local model stands in for both the cloud and the local model, so the answers differ only by what
each strategy sent, and the price is the ledger's fallback of 3 and 15 USD per million tokens.

```bash
curl -X POST 'localhost:8084/v1/runs?cases=20'   # runs in the background
curl localhost:8084/v1/runs/<run id>
curl localhost:8084/v1/runs/<run id>/chart.svg
```

Run of 4 November 2026: 20 prompts, 50 real values, seed 42, `qwen3.6:35b-a3b` as attacker and judge
(thinking), as both answer models (no thinking) and as the decision model (JSON schema, no thinking);
98 minutes on an Apple Silicon laptop; the names the decision model finds go out as surrogates. The raw
scores are in
[`docs/leak-lab-run-2026-11-04-surrogate-names.json`](docs/leak-lab-run-2026-11-04-surrogate-names.json).
Earlier runs: [`docs/leak-lab-run-2026-11-03-json-mode.json`](docs/leak-lab-run-2026-11-03-json-mode.json)
(named prompts kept local),
[`docs/leak-lab-run-2026-11-02-decision-model.json`](docs/leak-lab-run-2026-11-02-decision-model.json)
(the decision model in free text with a little thinking) and
[`docs/leak-lab-run-2026-11-02.json`](docs/leak-lab-run-2026-11-02.json) (without it).

| Strategy | Real values that left | Right person named | Without the name | Answer quality | Cloud cost | Mean latency |
|---|---|---|---|---|---|---|
| `NONE` | 50 of 50 | 20 of 20 | 10 of 10 | 4.7 | $0.098 | 6.2 s |
| `MASK` | 0 | 15 of 20 | 5 of 10 | 3.45 | $0.074 | 4.8 s |
| `PLACEHOLDER` | 0 | 16 of 20 | 6 of 10 | 4.8 | $0.077 | 5.1 s |
| `SURROGATE` | 0 | 13 of 20 | 3 of 10 | 4.8 | $0.099 | 6.3 s |
| `SURROGATE_MODEL` | 0 | 3 of 20 | 1 of 10 | 4.8 | $0.093 | 7.4 s |
| `LOCAL` | 0 | 0 | 0 | 4.7 | $0 | 6.1 s |

![Answer quality against people re-identified, per strategy](docs/leak-lab-tradeoff.svg)

What it says, with only 20 cases behind it:

- Hiding the identifiers is not hiding the person. With no real value left, the town and the job were
  still enough for the right person half of the time, five times a blind guess.
- Names are the guardian's biggest gap: it finds identifiers, not names, so a named prompt is
  recognised under every strategy that sends anything (10 of 10 with masks and with surrogates).
- Surrogates did a little better than masks because a fake EGN carries a fake date of birth, which
  points the attacker the wrong way. One case is ten points here, so this needs a larger run: the
  very first run of the same set gave 13 of 20 for surrogates and 15 for placeholders, so the attacker
  moves by a case or two between runs even at temperature 0. The four runs since 2 November differ
  by one case at most, and their quality grades by 0.1 at most.
- The decision model closes most of the name gap. When it kept named prompts local (run of
  3 November), no named prompt was recognised (0 of 10) and the cloud cost fell by three quarters.
  Now the names it finds go out as surrogates: 2 of 10 named prompts were recognised, against 10 of 10
  with surrogates alone, and those prompts reach the cloud model again, so the cost is back to what no
  protection costs. Prompts about a patient still stay local. `NAME: LOCAL` in the policy brings back
  the stricter trade.
- The way the model answers decides whether the slot is usable. Asked for free text with a little
  thinking, each check took about 20 seconds and the mean latency went from 6 to 32 seconds. Held to
  a JSON schema without thinking, the same model made the same calls in about a second, and the mean
  went from 6.1 to 6.9 seconds (7.4 with surrogate names,
  because named prompts reach the cloud model again).
- Masks cost answer quality (3.45 against 4.7): the answer keeps `***` where the user needed the
  phone or the EGN. Placeholders and surrogates come back as real values and lose almost nothing.
- Cost and latency follow the length of the answer, not the strategy: the guardian adds milliseconds,
  and a masked prompt gets a shorter, cheaper and worse answer. Surrogates cost what no protection
  costs, because the prompt looks just as real.
- `LOCAL` and `SURROGATE_MODEL` keep their quality only because the same model answers locally. With
  a weaker local model, the quality of what stays local is the number to watch.

## Repository layout

```
switchboard/
├── commons/        event contracts and shared types
├── gateway/        doorman, dispatcher, decision model slot
├── guardian/       privacy guard and judge
├── ledger/         prices, spend, budgets
├── trace/          masked traces, live view
├── leak-lab/       red team and evaluations
├── mock-llm/       fake OpenAI-compatible provider
├── console/        playground (plain HTML and JavaScript)
├── system-tests/   end-to-end tests against a deployed system
└── deploy/         Dockerfile and docker compose
```

| Service | Port |
|---|---|
| gateway | 8080 |
| guardian | 8081 |
| ledger | 8082 |
| trace | 8083 |
| leak-lab | 8084 |
| console | 8088 |
| mock-llm | 8090 |

## Run the demo

No Java and no build: CI publishes every service as an image after the smoke check passes, and the
compose file pulls them. It needs Docker (or Podman) and about 4 GB of memory.

```bash
git clone https://github.com/timganev/switchboard && cd switchboard
docker compose -f deploy/compose.yml up -d --wait --pull always --no-build
open http://localhost:8088          # the playground; the live view is at http://localhost:8083
```

The answers come from the fake model, which echoes the prompt, so every round trip is visible. With
[Ollama](https://ollama.com) running, `DECISION_MODEL=local` in front of the second command turns on the
decision model, and a name in the prompt goes out as a surrogate too (`DECISION_MODEL_NAME` picks the
model, `qwen3.6:35b-a3b` by default). `docker compose -f deploy/compose.yml down -v` stops it all.

## Getting started

Requires Java 25. Maven comes with the wrapper.

```bash
./mvnw verify                                   # build and run the tests
./mvnw -B package -DskipTests                   # build the jars
docker compose -f deploy/compose.yml up -d --wait   # infrastructure first, then all services
deploy/smoke.sh                                 # checks the whole stack end to end
```

`deploy/smoke.sh` issues an API key, sends requests through the gateway in both formats and checks that
every service is up, a request without a key or with a revoked one is refused, an EGN goes out as a
surrogate, a private key is blocked, the ledger keeps the team's budget, the trace merges each call with
its verdict, the real EGN is nowhere in OpenSearch and the playground shows the round trip. It works
the same with Podman (`podman compose`). Leak Lab in the stack reaches the models on the host at
`host.docker.internal:11434`; set `OLLAMA_URL` to use another endpoint.

The system tests read each service's base URL from an environment variable, for example
`GATEWAY_BASE_URL=http://localhost:8080`, and skip a service whose variable is not set.

### Try it without Docker

The gateway forwards to the OpenAI-compatible instances listed under `switchboard.backend.instances`
(by default one local instance: the fake provider on port 8090). Each instance has a `name`, a `url`,
an optional `api-key`, an optional `model` that replaces the client's model name, and a `locality`
(`LOCAL` or `CLOUD`; unset counts as cloud). Without Redis each gateway counts its own requests; set
`switchboard.board.type=redis` to share the board.

```bash
./mvnw -B package -DskipTests
java -jar mock-llm/target/mock-llm-0.1.0-SNAPSHOT.jar &
SWITCHBOARD_GUARDIAN_SECRET=a-long-random-secret-of-32-chars-or-more java -jar guardian/target/guardian-0.1.0-SNAPSHOT.jar &
java -jar gateway/target/gateway-0.1.0-SNAPSHOT.jar &
# Optional: events, budgets and traces need Kafka, Postgres and OpenSearch
docker compose -f deploy/compose.yml up -d kafka postgres opensearch
java -jar ledger/target/ledger-0.1.0-SNAPSHOT.jar &
java -jar trace/target/trace-0.1.0-SNAPSHOT.jar &

curl localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}'

curl localhost:8080/v1/messages -H 'Content-Type: application/json' \
  -d '{"model": "qwen36", "max_tokens": 100, "system": "Be brief.", "messages": [{"role": "user", "content": "Hi"}]}'

curl -N localhost:8080/v1/messages -H 'Content-Type: application/json' \
  -d '{"model": "qwen36", "max_tokens": 100, "stream": true, "messages": [{"role": "user", "content": "Hi"}]}'

# The verdict and its reasons come back in a header; the guardian shows what would leave
curl -i localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model": "qwen36", "messages": [{"role": "user", "content": "Risks for EGN 7501020018?"}]}'
curl localhost:8081/v1/inspect -H 'Content-Type: application/json' \
  -d '{"model": "qwen36", "messages": [{"role": "USER", "parts": [{"type": "text", "text": "Risks for EGN 7501020018?"}]}]}'
```

Leak Lab against the real guardian, with the decision model and without. It needs
[Ollama](https://ollama.com) 0.32 or newer (for `reasoning_effort`) and `ollama pull qwen3.6:35b-a3b`; any
OpenAI-compatible model works, see `leak-lab/src/main/resources/application.yml`. A run of 20 cases takes
one to two hours, so keep the machine awake. Finished runs are saved to `leak-lab-runs/` and loaded again
at start.

```bash
ollama serve &
export SWITCHBOARD_GUARDIAN_SECRET=a-long-random-secret-of-32-chars-or-more
java -jar guardian/target/guardian-0.1.0-SNAPSHOT.jar &
java -jar guardian/target/guardian-0.1.0-SNAPSHOT.jar --server.port=8085 \
  --switchboard.guardian.decision-model.type=local --switchboard.guardian.decision-model.url=http://localhost:11434 \
  --switchboard.guardian.decision-model.model=qwen3.6:35b-a3b --switchboard.guardian.decision-model.reasoning-effort=none &
java -jar leak-lab/target/leak-lab-0.1.0-SNAPSHOT.jar --switchboard.leak-lab.guardian-with-model.url=http://localhost:8085 &

curl -X POST 'localhost:8084/v1/runs?cases=20'
```

## Roadmap

- [x] **Skeleton:** all services start and report health, event contracts, fake provider, CI
- [ ] **1. Door and dispatcher:** coding agents work through Switchboard by changing only the base URL
  - [x] OpenAI and Anthropic formats through one internal model: text, system prompt, tool calls and results
  - [x] Both APIs forward to an OpenAI-compatible backend, with timeouts and errors in the caller's format
  - [x] Streaming in both formats; a backend error before the first byte is still a clean 502
  - [x] The in-flight board across local instances and a cloud provider, with retry inside the allowed set
  - [ ] Images and other multimodal content
- [ ] **2. Guardian:** deterministic detectors, deterministic surrogates and restore, fail-closed local route
  - [x] Detectors with check digits, a route per type, surrogates that pass the same checks, restore without storage
  - [x] Fail closed: no verdict means nothing is sent; a local request never leaves the local instances
  - [x] The answer is scanned for sensitive data the model produced on its own (`X-Switchboard-Response-Findings`)
  - [x] Restore inside a live stream, even when a surrogate is split across chunks
  - [x] Surrogates the model rewrites: separators, letter case, another phone prefix
  - [ ] High-entropy secrets without a known prefix
- [x] **3. Events:** ledger with budgets, trace, live view
  - [x] `llm-call-completed` from the gateway and `privacy-decision-made` from the guardian, off the request path
  - [x] Ledger: versioned prices, idempotent spend per team in Postgres, budget states on a compacted topic
  - [x] Gateway: a team over its budget is kept on local models, or refused with the reason
  - [x] Dead letter topics for consumers
  - [x] Trace: the call and its privacy decision merged into one masked document in OpenSearch
  - [x] Live view: every call with its route and reasons, pushed to the browser as it finishes
  - [x] Redrive endpoint for dead letters
  - [x] Reconciling the estimate with the provider's bill
- [ ] **4. Leak Lab and the decision model slot:** leakage, quality, cost and latency per strategy
  - [x] Leakage and re-identification per strategy, measured against the real guardian
  - [x] Answer quality, cost and latency per strategy, and the privacy versus utility chart
  - [x] The decision model slot, measured with and without
  - [x] The decision model in JSON mode (a JSON schema, no thinking): the same decisions on the 20 run
        prompts in about 1 s a check instead of 20 s
  - [x] A full run with the JSON mode: 1 of 20 recognised, a quarter of the cloud cost, 6.9 s mean latency
  - [ ] A larger run (all 40 people, several seeds), so one case is no longer ten points
  - [ ] A weaker local model for `LOCAL`, so its quality is measured rather than borrowed from the cloud stand-in
  - [ ] Recall and precision of the detectors and of the decision model, and the time each adds
  - [x] Surrogate names instead of keeping every named prompt local, so the cloud still gets the task
  - [ ] Runs published as an `eval-run-completed` event, and the chart on GitHub Pages
- [ ] **5. Hardening:** what a team would need before trusting it
  - [x] API keys per team, like OpenAI and Anthropic: `Authorization: Bearer sb-…` or `x-api-key`, only a
        SHA-256 hash in Postgres, the team taken from the key instead of a header, issue and revoke endpoints
  - [ ] `SKETCH`: the cloud model drafts on de-identified text, a local model fills in the real details
  - [ ] Lethal trifecta guard on the agent's tool list
  - [ ] The difficulty slot in the gateway: easy tasks to a cheaper model, measured like the privacy slot
  - [ ] Thinking models: forward or drop the `reasoning` field on purpose, and stop a thinking model from
        spending the whole `max_tokens` before it answers
  - [x] The whole stack from `deploy/compose.yml` started and checked end to end (`deploy/smoke.sh`)
  - [ ] Helm charts and system tests running as a Kubernetes Job, with Allure reports on GitHub Pages

**To decide**
- [ ] **Adaptive limits on top of the board.** The board counts requests, but the safe limit per
      instance is configured by hand. An AIMD controller (like TCP congestion control) could learn it
      from latency, 429 and 503, and also notice load that does not come through Switchboard. The learned
      limits would live next to the board in Redis.

## Tech stack

Java 25, Spring Boot 4, Kafka, Postgres with Liquibase, Redis, OpenSearch, Testcontainers,
WireMock, Docker, Helm, GitHub Actions.

## License

[Apache License 2.0](LICENSE)
