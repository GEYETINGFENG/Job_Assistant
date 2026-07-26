# Job Assistant

An AI-powered job assistant system built with **Spring Boot** and **LLM technologies**, designed to help users improve their job search process through intelligent resume analysis and job matching.

## Features

- User authentication and authorization
- Resume upload and management
- AI-powered resume parsing and analysis
- Job description matching and recommendation
- Resume version management
- Application progress tracking

## Tech Stack

### Backend
- Java 21
- Spring Boot 3
- Spring AI
- MyBatis Plus
- Maven

### Database & Storage
- MySQL
- Redis

### AI
- Large Language Model API
- Prompt Engineering
- Structured Data Extraction

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
