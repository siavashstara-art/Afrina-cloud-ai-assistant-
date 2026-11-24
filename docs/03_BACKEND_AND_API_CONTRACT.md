# Backend & API Contract Strategy (Self-Managed Linux VPS Ready)

## 1. Deployment Target (No Vendor Lock-In)
- **Target Host**: Any Debian/Ubuntu Linux VPS (2–4 vCPU, 4–8 GB RAM initially) running Docker Compose or systemd + rootless containerd.
- **Ingress**: Caddy or Nginx with automated Let's Encrypt TLS 1.3 and strict rate limiting.
- **Core Services**:
  - `afrina-api`: Kotlin/Ktor or FastAPI/Go control-plane service exposing `/v1/*`.
  - `postgres`: Primary relational store (projects, tasks, users, deployments, immutable audit log).
  - `redis`: Job queue and real-time log stream pub/sub (SSE/WebSocket to Android).
  - `afrina-runner`: Ephemeral container worker executing builds, tests, and sandboxed agent tools.

## 2. API Versioning & Client Contract (`ControlPlaneApiContract.kt`)
- Prefix all endpoints with `/v1/`.
- Typed DTOs generated with Moshi (`@JsonClass(generateAdapter = true)`).
- Idempotency keys (`Idempotency-Key` header) on build/deploy/approval mutations.
- Redacted `OkHttpClient` logging (`Authorization` and `X-Afrina-Signature` always masked).

## 3. Multi-User Expansion Readiness
- Single-founder workspace in Phase 0–1 uses an explicit `workspace_id` / `owner_id` column in schema contracts so multi-user RBAC (`OWNER`, `MAINTAINER`, `REVIEWER`) can be enabled without schema rewrites.
