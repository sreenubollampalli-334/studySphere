# Architecture decisions

Architecture decisions from Phase 00 onward.

| ADR | Decision and tradeoff |
|---|---|
| ADR-0001 · Modular monolith | Adopt the blueprint’s single Spring application with feature-owned data access and explicit services/events. One deployment simplifies initialization and transactions. Package boundaries need review discipline as the codebase grows. |
| ADR-0002 · Durable and derived data | MySQL owns durable state, Flyway evolves schema by phase, object storage holds bytes, and Qdrant is a rebuildable index. Retries and reconciliation handle failures between stores. |
| ADR-0003 · Browser auth | Use opaque bearer tokens held in browser memory and rotating refresh credentials in Secure, HttpOnly cookies. Persist only credential hashes. A family replay revokes the successor, and protected requests check both the session and the active account. Protect auth writes with a CSRF cookie and header. Use same-origin development and deployment. |
| ADR-0004 · Interfaces | Use versioned JSON REST and problem details, SSE for AI, and authorized STOMP for chat. Persist before publishing, then use history APIs for reconnect. |
| ADR-0005 · AI trust boundary | Keep providers configurable, limit usage, authorize retrieval, validate structured output, and make students approve proposed writes. Extra validation and review are part of feature delivery. |
| ADR-0006 · Native development services | Developer approved a Docker-free development setup during Phase 01. Run MySQL natively on workstations and CI. Integration tests require a dedicated empty MySQL schema. Future development services follow the same Docker-free constraint. |

## Open decision register

| Item | Proposed approach and validation | Owner and gate |
|---|---|---|
| Supported dependency versions | Verify Java 21, Spring Boot and Spring AI compatibility and frontend peer dependencies. Pin dependencies and lockfiles during scaffolding. | Engineering · Phase 01 scaffold |
| Cross-module deletion | Phase 02 deletes the account and its authentication data after a recent sign-in. Extend the deletion inventory and define retention as later modules introduce shared data. | Engineering and product · Each data-owning phase |
| Production email | Phase 02 uses configurable SMTP and a local inbox for development. Select production delivery, sender verification, and operational retry policy before enabling real mail. | Engineering · Production rollout |
| Planning calendar semantics | Choose supported recurrence rules and DST handling. Test repeated and skipped local times before enabling recurrence. | Engineering and product · Phase 04 calendar |
| Object storage and file rules | Compare S3-compatible options against private access, deletion, file type and size policy, and local test support. | Engineering · Phase 05 storage integration |
| Tutor provider, model, budgets | Compare Gemini and OpenAI on latency, cost and data handling using representative prompts. Keep configuration portable. | Engineering and product · Phase 08 provider rollout |
| Embeddings, extraction, retrieval | Evaluate extraction and real-page citations on representative PDFs/TXT, then tune chunking and retrieval and pin embedding dimensions. | Engineering · Phase 09 indexing |
| Revision algorithm and mastery policy | Choose a deterministic schedule and evidence thresholds using test fixtures and representative learner journeys. | Engineering and product · Phases 10–11 |
| Retention and shared-data deletion | Define ownership, retention and deletion per module, including messages, files, vectors, provider data and backups. Verify production policy before launch. | Product and operations · Owning phases and Phase 14 |
| Production platform and operations | Decide deployment target, restore objectives, backup schedules and alerts with measured load and cost data. | Operations · Phase 14 release |

Resolve each open choice in its owning phase. Continue under the developer's phase instructions. [Roadmap and checklist](../ROADMAP.md)
