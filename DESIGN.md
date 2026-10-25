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
