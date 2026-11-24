# AI Agent Architecture & Security Model

## 1. Seven Specialized Agents
1. **AI Software Architect**: Module design, OpenAPI/DB schema diff, ADRs.
2. **AI Programmer**: Scoped feature branch patches inside isolated workspace.
3. **AI Code Reviewer**: Static checks, concurrency/security review comments.
4. **AI Debugging Agent**: Log correlation and reproducible failure analysis.
5. **AI Testing Agent**: Unit, contract, and Robolectric test synthesis.
6. **AI Security Auditor**: Secret entropy scanning, SBOM/dependency audit, policy checks.
7. **AI Deployment Assistant**: Dry-run manifests, health checks, rollback orchestration.

## 2. Execution Lifecycle & Tool Boundaries
- Every agent runs with a declarative **Tool Allowlist** (e.g., `repo.read`, `gradle.test_unit`).
- Sensitive tools (`vps.exec_shell`, `deploy.production`, `git.push_protected`, `vault.rotate_keys`) emit an `ApprovalRequest` instead of executing.
- The founder approves or rejects from the Android `Agents` screen; every decision is persisted to `audit_logs`.

## 3. Android Security Baseline
- `android:allowBackup="false"` in `AndroidManifest.xml`.
- Secrets encrypted at rest via `AndroidKeystoreVault` (`AES/GCM/NoPadding`, 256-bit key in hardware Keystore).
- Zero hardcoded API keys, tokens, or signing passwords in source files.
- Minimal permissions (`INTERNET` only in Phase 0).
