# opti-workflow

Orchestrates the processes that touch more than one domain: `place-order` (reserve stock, open
the quotation) and `cancel-order` (cancel it, release the stock). Same contract as any `-api`
(token, envelope, idempotent start); what makes it different is the saga underneath.

Part of the OptiView distributed system (team `opti`). Governance and documentation live in
[`opti-docs`](https://github.com/code-corhuila/opti-docs).

## Sagas

| Saga | Steps (each with its compensation) |
|---|---|
| `place-order` | 1. check the patient is active (nothing to undo) → 2. reserve stock (undo: release it) → 3. open the quotation (undo: cancel it) |
| `cancel-order` | 1. cancel the order (**cannot be undone**: it is the gatekeeper) → 2. release its reserved stock |

`POST /api/v1/sagas/place-order` and `POST /api/v1/sagas/cancel-order`, `Idempotency-Key`
required: repeating it returns the same saga and runs nothing again. Every step sends its own
key, `<sagaId>:<step>`, so a retried step never reserves or charges twice.

## The three rules of a saga (Annex E)

1. **Every step declares its compensation**, next to the step. A step that cannot be undone
   (cancelling the order) goes last, or a later failure cannot be repaired.
2. **State is saved after every step**, in Redis. Where saga state lives in production is a
   team decision (numeral 5.8.4, no `-db` is assigned to a transversal); register it as an ADR
   in `opti-docs` (`05-architecture/decisions/`) with its criterion and its accepted cost. An
   orchestrator that only keeps state in memory cannot resume after a restart.
3. **Compensations run in reverse order and are idempotent.** They can run more than once — for
   example after a crash during the compensation itself.

If a compensation itself fails, the saga ends `FAILED`: automatic recovery is no longer safe and
a person decides. The response never hides this, but it never exposes the internal detail either
— `failedStep` and a closed-list `failureReason` (`PATIENT_NOT_FOUND`, `INSUFFICIENT_STOCK`…) go
in the response; server names, ports and stack traces stay in the saga's `detail` and in the log,
tied to the `traceId`.

## Resuming

A background task resumes every saga still `RUNNING`/`COMPENSATING` whose state has not changed
for `SAGA_RESUME_AFTER` (`SAGA_RESUME_EVERY` controls how often it looks). A short-lived lease in
Redis (`SAGA_LEASE`) keeps two instances of the workflow from running the same saga at once. A
temporary failure (a participant down) leaves the saga `RUNNING` for up to `SAGA_MAX_ATTEMPTS`;
past that it ends `FAILED`.

## Run

The whole platform is started from `opti-infra` (Redis is defined there, transversal to every
domain). Alone:

```bash
cp .env.example .env
mvn -B verify                                      # + the Redis test if TEST_REDIS_PORT is set
docker compose --env-file .env -f deploy/compose.yml build
```

## Depends on

`opti-customers-api`, `opti-products-api` and `opti-sales-api` (only through their published
APIs), Redis for its state, and its own service credential issued by `opti-auth-api`.
