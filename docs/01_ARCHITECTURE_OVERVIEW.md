# AFRINA AI Development Cloud — Phase 0 Architecture Overview

## 1. Product Vision
AFRINA AI Development Cloud is a personal AI-powered software factory operated primarily from a native Android smartphone (Persian-first RTL with full English LTR support) and engineered to connect to a self-managed Linux VPS control plane in subsequent phases.

## 2. System Topology
1. **Mobile Client (Android / Kotlin / Jetpack Compose)**:
   - Offline-capable control plane UI (`Dashboard`, `Projects`, `AI Agents`, `Build & Ops`, `Settings`).
   - Local persistence via **Room** (`projects`, `dev_tasks`, `approval_requests`, `agent_activities`, `notifications`, `audit_logs`).
   - User preferences via **DataStore Preferences** (`language`, `theme`, `selectedAiModelId`).
   - Hardware-backed secret vault via **Android Keystore (`AES/GCM/NoPadding`)**.
2. **Backend Control Plane (Future Linux VPS — Contract Defined in Phase 0)**:
   - Stateless REST + WebSocket/SSE API gateway behind TLS reverse proxy (Caddy/Nginx).
   - Worker job queue (Redis / PostgreSQL-backed) orchestrating Git sync, static analysis, container builds, and AI agent runs.
3. **Ephemeral Execution Sandboxes (Planned Phase 3)**:
   - Rootless containers (`containerd` / Docker with `seccomp`, dropped capabilities, read-only rootfs, network egress allowlists).
4. **Provider-Independent AI Gateway**:
   - `AiProviderGateway` interface decoupling agent workflows from OpenAI, Anthropic, Gemini, or self-hosted vLLM/Ollama models.
