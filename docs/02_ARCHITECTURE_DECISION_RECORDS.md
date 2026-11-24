# Architecture Decision Records (ADRs) — Phase 0

## ADR-001: Native Kotlin + Jetpack Compose (No WebView / Cross-Platform Wrapper)
- **Status**: Accepted
- **Decision**: Build exclusively with Kotlin, Jetpack Compose, Material 3, Coroutines, and StateFlow.
- **Rationale**: Native performance, first-class RTL (`LocalLayoutDirection`), direct Android Keystore access, and zero bridge overhead on smartphones.

## ADR-002: Package-by-Module Boundaries in `:app` for Phase 0
- **Status**: Accepted
- **Decision**: Organize `:app` with strict unidirectional package boundaries (`core.model`, `core.domain`, `core.data`, `core.network`, `core.ai`, `core.security`, `core.designsystem`, `feature.*`) rather than splitting 8+ Gradle subprojects in Phase 0.
- **Rationale**: Keeps Phase 0 builds fast and deterministic while enabling 1:1 extraction into `:core:*` and `:feature:*` Gradle modules in Phase 1 without changing ViewModels or repositories.

## ADR-003: Lightweight Constructor Injection via `AfrinaContainer`
- **Status**: Accepted
- **Decision**: Use manual constructor injection (`AfrinaContainer` + `ViewModelProvider.Factory`) in Phase 0.
- **Rationale**: Zero annotation-processor overhead, instant debuggability, and clean migration path to Hilt if multi-module complexity warrants it in Phase 2+.

## ADR-004: Provider-Independent Backend & AI Interfaces
- **Status**: Accepted
- **Decision**: Avoid Firebase lock-in or specific cloud-vendor SDKs in Phase 0; define `ControlPlaneApiContract` (Retrofit/Moshi) and `AiProviderGateway` backed by `LocalSimulated*` adapters.
- **Rationale**: Ensures the founder can deploy the backend onto any self-managed Linux VPS and swap LLM providers at any time.

## ADR-005: Human-in-the-Loop Approval Gate for Sensitive Operations
- **Status**: Accepted
- **Decision**: All destructive, privileged, or external-side-effect tool calls (`deploy.production`, `vps.exec_shell`, `git.push_protected`, `vault.rotate_keys`) require explicit founder confirmation recorded in `audit_logs`.
- **Rationale**: Prevents autonomous agent escalation or unintended infrastructure changes.
