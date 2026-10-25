# Design Notes

## 1. Version Number Strategy

Resume version numbers are generated using **atomic PostgreSQL SQL**.

The system does not use:

```text
Read latest_version_number
→ increment it in Java
→ write it back to the database
```

because concurrent requests could read the same version number and cause duplicate versions or conflicts.

The current approach lets the database complete the operation atomically:

```text
latest_version_number + 1
→ insert ResumeVersion
→ return the new version_number
```

It is also protected by:

```text
UNIQUE(resume_id, version_number)
```

as the final data-integrity constraint.

Atomic SQL is used because the version number is shared database state. Letting the database update it atomically avoids the race condition caused by a Java-side read-then-write process.

---

## 2. Pessimistic Locking

Pessimistic locking is used for:

```text
complete operations on the same uploadId
```

using:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

When two requests try to complete the same upload session at the same time:

```text
Request A → locks UploadSession → completes processing
Request B → waits for A → reads COMPLETED → does not process again
```

Pessimistic locking is used here because the same upload task should not be processed concurrently. The upload flow includes S3 operations, file parsing, and database writes, so duplicate execution would be expensive.

---

## 3. Optimistic Locking

Optimistic locking is used when editing an existing Resume Name


The Resume entity contains:

```java
@Version
private Long lockVersion;
```

Two requests can read and edit the same Resume concurrently.

If both initially read:

```text
lockVersion = 5
```

and request A updates first:

```text
lockVersion = 6
```

then request B still submits the stale value:

```text
lockVersion = 5
```

An optimistic locking conflict occurs during the update, preventing stale data from overwriting the newer change.

Optimistic locking is used because concurrent Resume edits are relatively uncommon. There is no need to lock the database row in advance; conflicts only need to be detected when an update is submitted.

---

## 4. Application Status State Machine

`application.status` used to be `INTEGER DEFAULT 0`: any number could be written and `OFFER` could silently move back to `APPLIED`. It is now a domain state machine (migration `V17`).

### Transition table

The table lives in `ApplicationStatus` and is the single source of truth:

| From | Allowed next |
|------|--------------|
| APPLIED | SCREENING, REJECTED, WITHDRAWN |
| SCREENING | INTERVIEW, REJECTED, WITHDRAWN |
| INTERVIEW | OFFER, REJECTED, WITHDRAWN |
| OFFER | ACCEPTED, REJECTED |
| ACCEPTED / REJECTED / WITHDRAWN | — (terminal) |

The lifecycle is irreversible: no status can move to an earlier status, and terminal statuses have no outgoing transitions.

### Where illegal transitions are rejected

- **Frontend**: `ApplicationDTO.allowedNextStatuses` lets a kanban board offer only legal moves. This is a convenience only; it can be bypassed.
- **Application layer (the real guard)**: `ApplicationServiceImpl.updateStatus` checks the transition table before touching the database and returns `400 INVALID_STATUS_TRANSITION`. The rules are in one place, readable and unit-tested (`ApplicationStatusTest`).
- **Database**: `CHECK` constraints only guarantee that each stored status value is valid. Expressing transition pairs in `CHECK` would need triggers and would duplicate the table, so the database does not enforce transitions.

### Concurrent status changes

`PATCH /applications/{id}` takes `{fromStatus, toStatus, note}`. The update is conditional:

```sql
UPDATE application SET status = :to, update_time = :now
WHERE id = :id AND user_id = :currentUser AND status = :from
```

If two requests both start from `SCREENING` (one moving to `INTERVIEW`, the other to `REJECTED`), only one matches the row. The other updates 0 rows and gets `409 APPLICATION_STATUS_CONFLICT`, so a decision made on a stale status can never overwrite a newer one. This uses the same idea as the upload worker's `claim_token`: the precondition is put in `WHERE`, and the database checks it atomically. A conditional update is simpler than adding `@Version` here, because the status itself is the precondition the client is relying on.

### Event history

Every status change appends one row to `application_event` (`from_status`, `to_status`, `operator_user_id`, `note`, `create_time`). It is written in the same transaction as the status update, so history and current status cannot disagree. Creating an application records `null → APPLIED`, and `V17` backfills that event for existing applications.

This is "current state + event history", not full event sourcing. Reads stay a single-row lookup, while the history still answers when an application moved and why.

### Cursor pagination for history

`GET /applications/{id}/events?cursor=&size=` returns the newest events first:

```sql
WHERE application_id = ? AND id < :cursor ORDER BY id DESC LIMIT size + 1
```

Event history is append-only, ordered by time and can grow large, so cursor pagination is used instead of `OFFSET`:

- **Fast**: `idx_application_event_application_id (application_id, id DESC)` seeks straight to the cursor; `OFFSET` scans and discards every skipped row.
- **Stable**: an event added while a user is paging does not shift later pages, so nothing is shown twice or skipped.
- Fetching `size + 1` rows tells whether a next page exists, without `count(*)`.

### Migration note

`V17` converts the column type in place (`INTEGER → VARCHAR`). An old instance writing `status = 0` during a rolling deploy would fail. That is acceptable while there is a single instance, but a multi-instance deployment should use expand-contract instead: add a new column, deploy code that writes both, then drop the old column in a later release.

---

## 5. Frontend Contract and Error Handling

The backend already offered refresh-token rotation, structured error codes and an async upload state machine, but the frontend used none of them: it kicked users to the login page on every 401, hand-wrote `if (status === 404)` per page, and never polled upload status. This section describes how the frontend now honours the contract.

### Error layering

All requests go through one axios instance (`frontend/src/api/apiClient.js`). Every failure is converted into an `ApiError` with `kind` (`network` / `timeout` / `canceled` / `http`), `status`, `code`, a user-facing `message` and a technical `detail`.

| Layer | Handles |
|-------|---------|
| Interceptor | 401 → silent refresh and replay; network, timeout and 5xx → global toast; `code != 0` on a 2xx response → error |
| `errors.js` mapping table | Backend `ErrorCode` → user-facing text. This is the single source of truth: the raw backend message is never shown, and a new code needs one line here |
| Page | Only page-specific meaning, e.g. a 404 on resume detail refreshes the list |

Every async view handles **loading / error / empty** explicitly (resume list, resume detail, upload).

Fixing the mapping exposed a backend bug: `ACCOUNT_CONFLICT` and `OPTIMISTIC_LOCK_CONFLICT` both used `40900`, so the frontend could not tell them apart, and the upload worker's `isRetryable` treated every `40900` (including generic constraint conflicts) as a retryable optimistic-lock conflict. The optimistic lock now uses `40903`, and generic database constraint conflicts use `DATA_CONFLICT (40904)` instead of reusing "account already exists".

### Silent refresh and why it needs a lock

Access tokens live 15 minutes. On a 401 the interceptor exchanges the refresh token for a new pair, then replays the failed request; the user only sees the login page when the refresh token itself is rejected.

Refresh tokens are single-use (rotation + reuse detection). If five requests get 401 together and each refreshes on its own, the first rotates `R1 → R2` and the other four submit the now-revoked `R1`. That is exactly the reuse signal, so the backend revokes the whole token family and logs out a legitimate user. The frontend's own refresh storm would trip the backend's security mechanism.

`tokenRefresher.js` therefore uses **single-flight**: the first 401 starts the refresh, and every other 401 awaits the same promise. A request that was sent with an old token but fails after another request already refreshed simply replays with the current token, without refreshing again. The unit test simulates a rotating backend and asserts one refresh call and no reuse detection for five concurrent 401s; removing the lock makes it fail.

Single-flight works within one tab. Two tabs refreshing at the same moment are covered by the backend's 5-second grace period (`refresh-concurrency-grace-seconds`).

A refresh that fails because of the network keeps the session (the user can retry once back online); only an explicit rejection from `/user/refresh` ends it. Logout now sends the refresh token so the backend actually revokes the family (it previously sent no body and always got 400).

### Polling the async upload with exponential backoff

Upload flow: presign (with `Idempotency-Key`) → `PUT` directly to S3 → `complete` (202) → poll `GET /resumes/uploads/{id}`.

- **Backoff**: 1s → 2s → 4s → 8s, capped at 8s. Status changes most right after submission; polling every second for a 30-second parse would send ~29 useless requests per user.
- **Every terminal state stops polling**: `COMPLETED` shows the result; `DEAD` (retries exhausted) and `EXPIRED` show a failure with the backend's reason and a retry button. Checking only `COMPLETED` would leave a `DEAD` task spinning forever.
- **Total timeout**: after 2 minutes without a terminal state, polling stops and the user is told it is still processing, with a "Check again" button.
- Network, timeout and 5xx errors while polling are retried; 4xx stops immediately. Polling is aborted when the component unmounts.

### OpenAPI: contract checked by the compiler

1. springdoc generates the contract from the backend code. `OpenApiContractIntegrationTest` exports `/v3/api-docs` and compares it with the committed `openapi/openapi.json`; changing an endpoint without regenerating the snapshot fails the backend build.
2. `npm run gen:api` turns the snapshot into `src/api/schema.d.ts` (openapi-typescript), and CI fails if the committed file is out of date.
3. The API layer (`api/*.ts`, `services/resumeUpload.ts`) calls `api(method, path, options)` from `typed.ts`. The path must exist in the contract, and path, query, header and body parameters plus the response type all come from it. Renaming a path or a field breaks `npm run typecheck`, so contract drift shows up at compile time instead of during integration or in production.

The Vue components stay JavaScript; type checking covers the API and service layer, which is where the contract is consumed.

**Regenerating after a backend API change:**

```bash
cd backend && OPENAPI_UPDATE=true ./mvnw verify -DskipUnitTests -Dit.test=OpenApiContractIntegrationTest
cd ../frontend && npm run gen:api && npm run typecheck
```

---

## 6. Job Matching Pipeline and LLM Generation Layer

`POST /matches` compares one **resume version** with one job description. The LLM call can take seconds and costs money, so it never runs on the request path.

### Async pipeline

`match_record` is a persistent task table (migration `V18`), using the same pattern as the resume upload tasks:

```text
POST /matches ─ insert PENDING ─> 202 ──> client polls GET /matches/{id}
                     │
MatchWorker ─ FOR UPDATE SKIP LOCKED ─> PROCESSING ─ LLM ─> COMPLETED
                                            │  retryable error, attempts left ─> PENDING (30s, 60s, 120s … backoff)
                                            └─ non-retryable or attempts used ─> DEAD
MatchRecoveryCoordinator: PROCESSING older than 5 min ─> PENDING (or DEAD)
```

- **At-least-once + idempotent processing**: tasks live in the database, so a crash never loses one. Each claim writes a fresh `claim_token`, and results are only saved if the token still matches, so a timed-out worker that finishes late cannot overwrite a newer worker's result.
- **Graceful shutdown** is shared with the upload worker through `TaskLifecycleGate`: stop claiming, finish in-flight work, then let Spring shut down.

### Idempotency

A unique index on `(resume_version_id, job_id, engine, prompt_version)` means each combination is analysed once:

| Existing record | `POST /matches` returns |
|-----------------|-------------------------|
| none | inserts PENDING, **202** |
| PENDING / PROCESSING | the same task, **202** |
| COMPLETED | the stored result, **200** (no LLM call) |
| DEAD | resets to PENDING, **202** (user retry) |

The insert is `INSERT … ON CONFLICT DO NOTHING`. When identical requests race, exactly one inserts and the others read the existing row. The database constraint acts as the result cache, so no separate Redis cache is needed. Changing the prompt bumps `prompt_version`, so old results are never mistaken for new ones.

### LLM generation layer (Lesson 8)

1. **Structured output**: JSON Mode only guarantees valid JSON. `MatchAnalysisValidator` also checks the business shape: integer score 0–100, every field present, arrays of non-empty strings with item-count and length caps, no skill both matched and missing. Failing output counts as a retryable failure.
2. **Prompt injection**: the job description and resume are untrusted, so they are wrapped in `<job_description>` and `<resume>` tags and the system prompt says to ignore instructions inside them. Prompts cannot fully stop injection, so the design limits the blast radius instead:
   - The model can only turn text into JSON; it triggers no actions.
   - Size caps limit how much injected text can reach the result.
   - "Matched" skills must literally appear in the resume, so "say the candidate is an expert in everything" adds nothing.
3. **Cost**: `usage.prompt_tokens / completion_tokens` are stored per match (attributed to the user) and exported as the Micrometer counter `llm.tokens{feature=match,type=…}`. A per-user **daily quota** (default 20, `MATCH_DAILY_QUOTA`) is enforced with an atomic Redis Lua script and returns `429 QUOTA_EXCEEDED`.
   - Only new analyses consume quota; returning an existing result does not.
   - The counter is incremented *after* the insert succeeds, and exceeding the limit throws inside the same transaction, which rolls the insert back. A racing loser never inserts, so it never consumes quota.
   - The counter lives in Redis because quota must be shared across instances, and it fails closed if Redis is unavailable.
4. **Reliability**: timeouts, 5xx, 429 and invalid output are retried with backoff. Other 4xx errors (for example 401/403 from a bad API key) go straight to DEAD as `LLM_REJECTED`, because retrying cannot succeed and would only waste quota. Rate-limiter waits defer the task without counting an attempt.

### Keyword fallback

When AI is disabled (`MATCH_AI_ENABLED` or `RESUME_AI_ENABLED` false), `KeywordMatchAnalyzer` compares the resume's declared skills with the job text using symbol-aware word boundaries (so `Java` does not match `JavaScript`, and `C++` is matched correctly). It also lists common technologies the job mentions but the resume lacks. Results are deterministic, free, and marked `engine = KEYWORD` so the UI can say it ignores experience and seniority. Because `engine` is part of the unique key, keyword results never block a later LLM analysis.

## 7. RAG Retrieval Layer (Lesson 7)

Before Lesson 7 the matcher put the **whole resume JSON** into the prompt. Now the job description is split into requirements (`RequirementExtractor`, at most 15). For each requirement the matcher searches the resume for evidence, so the result can say *where* the resume supports a requirement, or that nothing does. Every number below comes from `RagEvaluationTest`. The full report is in [`docs/rag-evaluation.md`](docs/rag-evaluation.md), built from 4 fictional resumes and 34 labelled queries (28 with a relevant section, 6 with none).

```text
MatchWorker ─ ensureIndexed(version) ─ chunk ─ cache lookup ─ embed misses ─ resume_chunk (V19)
            └ retrieve(version, JD) ─ requirements ─ embed ─ exact cosine top-3 ─ threshold ─ evidence
            └ analyze(resume, JD, evidence)  →  keyword-v2 / match-v2
```

### Chunking: by resume structure

`ResumeChunker` emits one chunk for the summary and one for the skills list, one per experience entry, one per project and one per education entry, and one for certificates. Each chunk gets a section label and a deterministic key (`experience:0`, `project:1`, `skills`), and the evaluation set is labelled with those keys. Resumes that only have raw text fall back to fixed-size 800/150 chunks.

Comparing strategies at the same k is misleading. A resume cut into 800-character chunks has only about 2 chunks, so top-3 returns almost the whole resume and scores 100% Recall@3. The real constraint is how much evidence fits in the prompt, so the fair comparison uses the **same 800-character budget**:

| Strategy | Recall | Context precision |
|---|---|---|
| structured | **89.3%** | 31.7% |
| fixed-300/50 | 85.7% | 33.2% |
| fixed-800/150 | 82.1% | 27.8% |

Structured chunks also keep one experience entry together with its company and dates, so the evidence shown to the user reads as a single statement.

### Top-k and threshold

- **k = 3**: Recall@k is 55.4% at k = 1, 89.3% at k = 3 and 96.4% at k = 5. Going from 3 to 5 gains 7 points of recall but drops precision from 31% to 20%, and every extra chunk is prompt tokens.
- **Similarity threshold 0.15**: vector search always returns k results, even when nothing in the resume is relevant. Without a threshold the model is handed unrelated text and encouraged to cite it. The threshold sweep chose 0.15 by Youden J: it rejects all 6 queries that have no relevant section, and keeps correct evidence for 32% of the positive queries. Lexical similarity scores are low in absolute terms, which is why the threshold sits so low. **The threshold belongs to the embedding model.** It must be re-tuned when switching to DashScope, whose cosine values have a completely different distribution.

### Embeddings: pluggable, with a deterministic default

`EmbeddingClient` has two implementations, chosen by `app.rag.embedding.provider`:

- `local-hash-v1` (default): feature hashing of words and word bigrams into 1024 dimensions, with sublinear term frequency and L2 normalisation. It is lexical retrieval in vector form: free, offline and reproducible, so tests and the evaluation do not depend on an API key. It cannot match synonyms. "Microservices" vs "split the monolith into services" is a miss, and most of the remaining failures in the per-query table are of this kind.
- `dashscope`: `text-embedding-v4` through the OpenAI-compatible `/embeddings` endpoint, in batches of up to 10 inputs. Each batch takes one rate-limiter token. Error classification is shared with the LLM client, so a rejected key goes straight to DEAD instead of being retried.

**Stemming was tested and turned off.** It lowered Recall@3 from 89.3% to 82.1%. Stemming collapses generic words such as "application", "model" and "deploy" into the same features, and those words appear in almost every chunk, so the distinctive terms lose weight. (Building the stemmer also caught a bug where "databases" and "database" stemmed to different forms.)

Planned follow-up: once the DashScope key works, let `RagEvaluationTest` choose the embedding client, then run the same evaluation with DashScope. Compare it with the hash baseline and re-tune k and the threshold for that model. Today the evaluation only runs with `local-hash-v1`.

### Storage: pgvector, exact search, no ANN index

`resume_chunk` (V19) stores the chunk text, `content_hash` (SHA-256), `embedding_model` and a `vector(1024)` column. Chunks are derived data, so they are deleted with their resume version (`ON DELETE CASCADE`) and can be rebuilt at any time.

- **No HNSW / IVFFlat index.** Retrieval is always limited to one resume version, which has a handful to a few dozen chunks. The unique index `(resume_version_id, embedding_model, chunk_key)` finds those rows, and computing exact cosine distance over them is fast and gives 100% recall. An approximate index trades recall for speed, which only pays off when searching across a large corpus. Combined with a selective `WHERE resume_version_id = ?` filter it is actually worse: the index returns the nearest neighbours first and filters them afterwards, so it can return fewer than k results. An ANN index becomes worth adding for cross-resume search, such as "which of my resumes best fits this job".
- **Model isolation**: vectors from different models are not in the same space. Every query filters on `embedding_model`. After switching models, old vectors are ignored and the version is re-embedded on demand.
- **Idempotent indexing**: `INSERT … ON CONFLICT DO NOTHING` on the unique index. When two workers index the same version concurrently, each chunk is still stored once.

### Embedding cache

`ResumeIndexingService.ensureIndexed` looks up existing vectors by `(content_hash, embedding_model)` before calling the embedding model. A new resume version that changes one project entry re-embeds only that chunk; every unchanged section reuses its stored vector. Embedding calls happen outside the database transaction, so a slow API never holds a connection. The metric `rag.embedding.cache{result=hit|miss}` shows the hit rate.

### Effect on matching

- Each requirement becomes a `RequirementEvidence { requirement, covered, evidence[] }`. The UI's **Requirement coverage** list shows ✓/✗, the best excerpt, its section and its similarity.
- The LLM prompt now sends the skills list plus deduplicated evidence excerpts instead of the whole resume. It falls back to the whole resume when retrieval finds nothing. Coverage comes from retrieval, not from the model, so the model cannot claim evidence that does not exist.
- The output shape changed, so `prompt_version` was bumped to `keyword-v2` / `match-v2`. Earlier results stay stored but are not reused for new requests.
