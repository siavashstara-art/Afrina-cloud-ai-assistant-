# Database Design & Phased Development Roadmap

## 1. Database Design
### Local Mobile Database (Room — Implemented in Phase 0)
- `projects`: Workspace catalog, repo coordinates, stack tags, health & build status.
- `dev_tasks`: Project-linked tasks with priority, status (`OPEN`, `IN_PROGRESS`, `REVIEW`, `DONE`), and assigned agent.
- `approval_requests`: Sensitive agent tool proposals awaiting founder sign-off.
- `agent_activities`: Timeline of simulated/remote agent runs.
- `notifications`: System alerts and approval reminders.
- `audit_logs`: Append-style security and mutation audit records.

### Cloud PostgreSQL Schema (Planned Phase 1)
Mirrors the local Room entities with `workspace_id`, `actor_user_id`, `created_at`, `updated_at`, plus `git_connections`, `ci_runs`, `deployments`, and `ai_usage_ledger` (token & cost tracking per provider).

## 2. Phased Roadmap
- **Phase 0 (Delivered)**: Architecture, ADRs, Room + DataStore + Keystore vault, bilingual Persian RTL/English LTR Jetpack Compose shell, 5-tab adaptive navigation, local/simulated adapters clearly labeled.
- **Phase 1 (Core Backend & Auth)**: Deploy `afrina-api` + PostgreSQL on self-managed Linux VPS, device/token auth (Ed25519 / OIDC), real-time project & task sync.
- **Phase 2 (GitHub & AI Provider Adapters)**: GitHub App/OAuth repo inspector, real `AiProviderGateway` adapters (OpenAI/Anthropic/Gemini/vLLM) with token/cost metering.
- **Phase 3 (Sandboxed Runner & CI Quality)**: Rootless Docker/gVisor job runner, live streaming logs via WebSocket/SSE, automated test & SAST gates.
- **Phase 4 (CD & Mobile IDE Extensions)**: Container/PWA/API deployment with one-tap rollback, mobile diff viewer, and controlled terminal session viewer.
- **Phase 5 (Multi-User & Hardening)**: Team RBAC, hardware passkeys, external audit export, and high-availability worker nodes.
