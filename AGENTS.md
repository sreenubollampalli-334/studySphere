# StudySphere repository instructions

Read the [master blueprint](StudySphere_Codex_Master_Blueprint.pdf), [roadmap](docs/ROADMAP.md), approved decisions, and the relevant specifications. Inspect existing files before changes. Treat the blueprint as the master specification. Raise conflicts between the blueprint and any proposed design in the phase review. Implement approved phases in order. Phase 00 is documentation only. Honor explicit phase-by-phase or standing continuation instructions. Obtain approval before destructive changes.

- Use Java 21, Maven, Spring Boot, Spring AI, MySQL and Flyway, and React TypeScript with Vite. Keep AI providers configurable. Introduce S3-compatible storage and Qdrant in their phases and Redis when justified.
- Keep a feature-organized modular monolith. Use thin controllers, DTOs, validated services, explicit service interfaces or application events, and feature-owned repositories.
- Use UTC instants, explicit local scheduling zones, foreign keys, constraints, indexes, transactions, and forward-only migrations. Preserve applied Flyway files. Restrict automatic schema mutation to disposable tests.
- Derive identity from the authenticated principal. Authorize resource access and all socket sends and subscriptions. Validate inputs, restrict CORS, protect cookie flows against CSRF, hash passwords, and rotate and revoke refresh credentials. Supply secrets through the environment and keep them out of source and logs.
- Treat AI output and retrieved text as untrusted. Validate output, enforce retrieval permissions, budget usage, require explicit user approval for proposed writes, and cite only real sources.
- Build reusable, accessible, responsive UI with light and dark themes, real API data, and loading, empty, error, and success states. Hide unavailable modules.
- Preserve unrelated behavior. Define migration, API, UI, and test changes before coding. Test business rules and authorization, exercise MySQL migrations, run applicable build and lint checks, and update documentation.
- At the phase gate, report changed files, migration IDs, API changes, exact commands and observed results, manual verification steps, known issues, and risks. Report applicable checks that could not be run. Mark the phase complete only after its acceptance checks pass, then proceed when approval covers the next phase.
