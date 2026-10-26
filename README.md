# Job Assistant

[![CI](https://github.com/GEYETINGFENG/Job_Assistant/actions/workflows/ci.yml/badge.svg)](https://github.com/GEYETINGFENG/Job_Assistant/actions/workflows/ci.yml)

An AI-powered job assistant system built with **Spring Boot** and **LLM technologies**, designed to help users improve their job search process through intelligent resume analysis and job matching.

## Features

- **Accounts**: sign-in with short-lived JWT access tokens and rotating refresh tokens (reuse detection revokes the whole session family).
- **Resume upload and parsing**: files go straight to S3 through presigned URLs. A background worker validates the file, extracts text and parses it with an LLM into structured JSON. The output is schema-validated and contact details are checked against the source text.
- **Resume versions**: every upload of an existing resume becomes a new immutable version.
- **Job matching**: save a job description and match it against any resume version. Matching runs asynchronously and returns a score, matched and missing skills, gaps and suggestions. It uses an LLM, with a keyword fallback when AI is disabled.
- **Requirement coverage**: the job description is split into requirements, and retrieval over the resume (pgvector) shows the evidence for each one, or says that none was found.
- **Application tracking**: a status state machine with an event history.
- **Cost control**: Redis caching of parse results, a global LLM rate limiter, per-user daily match quota, and token usage recorded per task.

## Documentation

| Document | Contents |
|---|---|
| [`DESIGN.md`](DESIGN.md) | Design decisions: concurrency and idempotency, state machines, authentication tokens, async task delivery, LLM layer, RAG, caching and rate limiting |
| [`RESUME-UPLOAD-DESIGN.md`](RESUME-UPLOAD-DESIGN.md) | Upload security: ownership, presigned uploads, file-content checks, storage and cleanup |
| [`docs/rag-evaluation.md`](docs/rag-evaluation.md) | Retrieval evaluation report (chunking, top-k, threshold, embedding models) |
| [`docs/performance-report.md`](docs/performance-report.md) | Load test results |
| [`docs/llm-rate-limit.md`](docs/llm-rate-limit.md) | LLM token bucket parameters and how to test it (Chinese) |
| [`docs/resume-cache-apifox.md`](docs/resume-cache-apifox.md) | Resume parse cache setup and manual acceptance steps (Chinese) |

## Tech Stack

### Backend
- Java 17
- Spring Boot 3.3 (Web, Validation, Actuator)
- Spring Data JPA (Hibernate) + Flyway migrations
- Spring Security + OAuth2 Resource Server (JWT access tokens, rotating refresh tokens)
- Apache Tika (PDF / DOCX text extraction with zip-bomb limits)
- Maven

### Database & Storage
- PostgreSQL (JSONB for parsed resumes, pgvector for resume chunk embeddings)
- Redis (resume parse cache, global LLM token-bucket rate limiter, daily match quota)
- Amazon S3 (presigned uploads, resume file storage)

### AI
- Alibaba Cloud Bailian (DashScope, OpenAI-compatible API) via Spring `RestClient`
- Structured output with business schema validation for resume parsing and match analysis
- RAG: structured resume chunking, `text-embedding-v4` or a local hashing embedding, exact cosine search in pgvector

### Frontend
- Vue 3 + Vite, TypeScript API types generated from the backend's OpenAPI contract
- Pinia, Vue Router, Axios (silent token refresh with a single-flight lock)

### Engineering
- Testing: JUnit 5, Mockito, Testcontainers (PostgreSQL / Redis)
- CI: GitHub Actions (backend lint, unit tests, then integration tests; frontend contract check, lint, type check, Vitest and build)
- Lint: Checkstyle (backend) and ESLint (frontend), limited to rules that catch bugs and dead code
- Observability: Micrometer + Prometheus metrics, structured JSON logs with request IDs
- Deployment: Docker (multi-stage, non-root) + docker-compose
- Load testing: k6 (see [performance report](docs/performance-report.md))

## Running Tests

Tests are split into fast unit tests and slower integration tests (run from `backend/`):

| Command | What runs | Needs Docker |
|---|---|---|
| `./mvnw test` | Unit tests only (Mockito, no containers), a few seconds | No |
| `./mvnw verify` | Unit tests, then integration tests tagged `@Tag("integration")` (Testcontainers PostgreSQL / Redis, full Spring context) | Yes |

New tests that need a real database, Redis or the full application context must be annotated with `@Tag("integration")`; everything else should stay a plain unit test.

### Lint

| Command | What it checks |
|---|---|
| `./mvnw validate` (in `backend/`) | Checkstyle with [`config/checkstyle/checkstyle.xml`](backend/config/checkstyle/checkstyle.xml). It also runs at the start of `./mvnw test` and `./mvnw verify`, so a violation fails the build. |
| `npm run lint` (in `frontend/`) | ESLint with [`eslint.config.js`](frontend/eslint.config.js): JavaScript recommended rules, the Vue plugin's essential rules, and typescript-eslint for `.ts` files. |

Both rule sets only catch likely bugs and dead code: unused imports and variables, `==` on strings, `equals` without `hashCode`, switch fall-through, empty `catch` blocks, `System.out` instead of a logger, invalid Vue templates. Pure formatting rules (indentation, line length, attribute wrapping) are deliberately left out. They would rewrite the whole codebase for little benefit and bury real changes in the diff.

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs the same split on every push to `main` and every pull request: the `Unit tests` job runs first, and `Integration tests` starts only after it passes. Maven dependencies are cached between runs, and no repository secrets are required.

## Security Design

### 1. RBAC

The system defines two roles: `USER` and `ADMIN`.

Role-based access control is implemented at the API path level in Spring Security:

- All endpoints under `/admin/**` require `ROLE_ADMIN`.
- Other protected endpoints require a valid JWT.
- For example, `GET /admin/users` can only be accessed by an administrator.
- An authenticated `USER` accessing an administrator endpoint receives `403 Forbidden`.

API-level control was selected because administrator endpoints are grouped under a clear `/admin/**` path. This keeps the authorization rules centralized and reduces the risk of missing role annotations on individual controller methods.

### 2. Resource Ownership

Resume ownership validation is implemented in the Service layer, while the Repository layer applies the actual ownership filter.

The current user ID is read from the authenticated JWT through `CurrentUserProvider`. Queries include both the requested resource ID and the current user ID:

```sql
SELECT *
FROM resume
WHERE id = :resumeId
  AND user_id = :currentUserId;
```

The same rule applies to read, update, and delete operations. List operations only query resources whose `user_id` equals the current user ID. When creating a resume, the owner ID is obtained from the JWT and is never trusted from a client request.

This design was selected because resource ownership is a business rule and therefore belongs in the Service layer. Applying `id + user_id` filtering directly in database operations prevents another user's data from being loaded.

### 3. 404 Instead of 403

Queries include both the resource ID and the current user ID, so the system does not distinguish between “the resource does not exist” and “the resource belongs to another user.”

Both cases return `404 Not Found`, preventing attackers from enumerating IDs to discover whether other users' resumes exist.

`403 Forbidden` is reserved for known role-based restrictions, such as a `USER` accessing an `ADMIN` endpoint.

This provides consistent responses while reducing resource-existence information leakage.

### 4. CORS

CORS is configured globally in Spring Security. Only the configured frontend origin is allowed.

The configuration permits:

- `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, and `OPTIONS`
- `Authorization`, `Content-Type`, and `Accept` request headers
- JWT transmission through `Authorization: Bearer <token>`

Browser preflight `OPTIONS` requests are permitted without authentication. Cross-origin cookies are disabled because authentication uses a Bearer Token rather than a Cookie-based Session.

### 5. Current Limitations and Risks

- **Access tokens cannot be revoked early.** A JWT stays valid until it expires (15 minutes). Refresh tokens *can* be revoked: signing out revokes the session family, reuse detection revokes it automatically, and deleting a user revokes all of that user's sessions.
- **Role changes are delayed.** Roles are read from the access token, so removing admin rights takes effect within 15 minutes, when the next token is issued.
- **The refresh grace window depends on Redis.** It is shared by all instances, but while Redis is unavailable two near-simultaneous refreshes of the same token are treated as reuse and sign the user out.
- **No password change or account disable yet.** When added, they must revoke all of the user's refresh tokens, as account deletion already does.
- **Browser uploads need S3 CORS.** The bucket must allow `PUT` from the frontend origin, otherwise the browser cannot upload to the presigned URL.
- **Retrieval limits.** Evidence for a named technology requires that technology to appear in the resume text, using a fixed technology vocabulary. The evaluation set has 49 labelled queries, so thresholds should be re-checked as real job descriptions accumulate.
- **No infrastructure as code yet.** Deployment is Docker and docker-compose; the cloud resources (RDS, ElastiCache, ECS, ALB) are not described in Terraform.

See [`DESIGN.md`](DESIGN.md) for the reasoning behind these trade-offs.
