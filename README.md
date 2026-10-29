# Switchboard

An AI gateway in Java that keeps sensitive data close to home and measures whether it succeeds.

Every company now wants its teams to use the strongest cloud models without sending out data that
must not leave. Switchboard is the single door all LLM traffic goes through. For every request it
decides where the request may go, sends it there, and records why.

> **Status:** skeleton. Every service starts and reports health; the logic is built stage by stage
> (see [Roadmap](#roadmap)).

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
| `gateway` | **Doorman and dispatcher.** Authenticates teams, translates the OpenAI and Anthropic formats into one request, asks the guardian for a verdict, picks the least busy healthy model instance inside the allowed set, streams the answer back. Holds the decision model slot for task difficulty | Redis: the in-flight board (shared by all gateway replicas) |
| `guardian` | **Guardian and judge.** Finds sensitive data in every part of the request, has the final word on the route, swaps values for surrogates and restores them, guards agents against the lethal trifecta. Holds the decision model slot for unclear sensitivity, which accepts only a local model. A separate process so the security boundary can be tested and attacked on its own | Nothing at rest |
| `ledger` | **Accountant.** Prices every call, aggregates spend per team, publishes the budget state | Postgres: the system of record for money |
| `trace` | **Black box.** What happened and why; feeds the live view | OpenSearch: masked traces and decisions, never raw sensitive values |
| `leak-lab` | **Red team.** Attacks the protection through the gateway and measures leakage, quality, cost and latency for every strategy | Postgres: datasets, runs, results |
| `mock-llm` | Fake OpenAI-compatible provider for tests and offline demos | No |
| `commons` | Event contracts and shared types | — |
| `system-tests` | End-to-end tests against a deployed system | — |

## The hot path

Example: an agent sends a contract with a national ID and an IBAN and asks for a risk summary.

```
client ──► gateway: authenticate, translate into the internal format
              │
              ├──► guardian /inspect ── verdict: SURROGATE, reason: NATIONAL_ID, IBAN
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
client ◄──────┘    header: X-Switchboard-Route: surrogate; reason=NATIONAL_ID,IBAN
              │
              └──► Kafka: llm-call-completed  (asynchronous, off the client's path)
```

The guardian scans every part of the request: system prompt, messages, tool arguments and tool
results. It returns one of five verdicts:

| Verdict | What happens |
|---|---|
| `CLOUD` | Straight to a cloud model |
| `SURROGATE` | Realistic fake values go out, the real ones are restored in the answer |
| `SKETCH` | The cloud model drafts on de-identified text, a local model fills in the real details |
| `LOCAL` | Local model only; fails closed if none is available |
| `BLOCK` | Rejected, e.g. a private key in the prompt |

### A vault that does not exist

Surrogates are deterministic: `HMAC(session key, real value)` becomes a fake but valid-looking value.
Chat APIs resend the whole history on every turn, so the guardian sees the real values again and turns
them into the same surrogates. The reverse mapping is needed only within one request, so it lives in
memory and nothing sensitive is stored at rest. For the same reason sensitivity sticks to a session
without being remembered: the sensitive value is still in the history. The session key is per
conversation, so a provider cannot link the same value across conversations.

### The in-flight board

Redis has exactly one job: a shared board of how many requests each model instance is working on
right now. A small Lua script reads the board, picks the least busy instance and increments its
count in one atomic step, so several gateway replicas never pile onto the same instance while another
is idle. Local models serve only a few requests at once, so this has a direct effect on latency.
Counts are leases with a TTL, so a crashed gateway cannot leave an instance marked busy forever. If
Redis is down, each replica falls back to counting its own requests.

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

## Leak Lab

A second model receives exactly what left the boundary and tries to recover what was hidden or to
re-identify the person from what is left (age, job and town are often enough). Every strategy, from
no protection through placeholders and surrogates to `LOCAL`, is scored on four numbers: leakage,
answer quality, cost and latency. The goal is a privacy versus utility chart in this README, backed
by a reproducible run.

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
| mock-llm | 8090 |

## Getting started

Requires Java 25. Maven comes with the wrapper.

```bash
./mvnw verify                                   # build and run the tests
./mvnw -B package -DskipTests                   # build the jars
docker compose -f deploy/compose.yml up -d      # infrastructure and all services
curl localhost:8080/actuator/health
```

The system tests read each service's base URL from an environment variable, for example
`GATEWAY_BASE_URL=http://localhost:8080`, and skip a service whose variable is not set.

## Roadmap

- [x] **Skeleton:** all services start and report health, event contracts, fake provider, CI
- [ ] **1. Door and dispatcher:** OpenAI and Anthropic formats, streaming, the in-flight board across
      local instances and a cloud provider; coding agents work through Switchboard by changing only the base URL
- [ ] **2. Guardian:** deterministic detectors, deterministic surrogates and restore, fail-closed local route
- [ ] **3. Events:** ledger with budgets, trace, live view
- [ ] **4. Leak Lab and the decision model slot:** leakage, quality, cost and latency per strategy
- [ ] **5. Hardening:** `SKETCH`, lethal trifecta guard, Helm charts, system tests in Kubernetes, Allure reports

**To decide**
- [ ] **Live view UI (stage 3).** Options: Thymeleaf + htmx + server-sent events served by Spring
      (stays in Java, no Node toolchain), React + Vite (stronger full stack signal, second toolchain),
      or Grafana dashboards (little code, but shows numbers rather than per-request reasons).
- [ ] **Adaptive limits on top of the board.** The board counts requests, but the safe limit per
      instance is configured by hand. An AIMD controller (like TCP congestion control) could learn it
      from latency, 429 and 503, and also notice load that does not come through Switchboard. The learned
      limits would live next to the board in Redis.

## Tech stack

Java 25, Spring Boot 4, Kafka, Postgres with Liquibase, Redis, OpenSearch, Keycloak, Testcontainers,
WireMock, Docker, Helm, GitHub Actions.

## License

[Apache License 2.0](LICENSE)
