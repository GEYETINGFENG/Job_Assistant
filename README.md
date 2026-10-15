# Job Assistant

[![CI](https://github.com/GEYETINGFENG/Job_Assistant/actions/workflows/ci.yml/badge.svg)](https://github.com/GEYETINGFENG/Job_Assistant/actions/workflows/ci.yml)

An AI-powered job assistant system built with **Spring Boot** and **LLM technologies**, designed to help users improve their job search process through intelligent resume analysis and job matching.

## Features

Redis 简历解析缓存的环境配置和手工验收步骤见 [Apifox 验收指南](docs/resume-cache-apifox.md)。
全局令牌桶参数和限流重试验收见 [LLM 全局限流指南](docs/llm-rate-limit.md)。

- User authentication and authorization
- Resume upload and management
- AI-powered resume parsing and analysis
- Job description matching and recommendation
- Resume version management
- Application progress tracking

## Tech Stack

### Backend
- Java 17
- Spring Boot 3.3 (Web, Validation, Actuator)
- Spring Data JPA (Hibernate) + Flyway migrations
- Spring Security + OAuth2 Resource Server (JWT access tokens, rotating refresh tokens)
- Apache Tika (PDF / DOCX text extraction with zip-bomb limits)
- Maven

### Database & Storage
- PostgreSQL (JSONB for parsed resumes)
- Redis (resume parse cache, global LLM token-bucket rate limiter)
- Amazon S3 (presigned uploads, resume file storage)

### AI
- Alibaba Cloud Bailian (DashScope, OpenAI-compatible API) via Spring `RestClient`
- Prompt engineering for structured resume extraction, with JSON validation

### Frontend
- Vue 3 + Vite
- Pinia, Vue Router, Axios

### Engineering
- Testing: JUnit 5, Mockito, Testcontainers (PostgreSQL / Redis)
- CI: GitHub Actions (unit tests, then integration tests)
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

- JWTs cannot be immediately revoked before expiration.
