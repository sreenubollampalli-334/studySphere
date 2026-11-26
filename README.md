# StudySphere

**Learn Smarter. Together.**

StudySphere brings planning, tutoring, practice, revision, and peer collaboration into one study workspace for college students and independent learners.

**Status** · Phases 00–13 complete. Phase 14 adds production hardening.

The [master blueprint](StudySphere_Codex_Master_Blueprint.pdf) defines the product and phase gates. The Markdown specifications record how the team proposes to realize it.

| Topic | Specification |
|---|---|
| Scope, stories, and module acceptance | [Product requirements](docs/PRODUCT_REQUIREMENTS.md) |
| System boundaries and threat model | [Architecture](docs/ARCHITECTURE.md) |
| Early-module entities and transactions | [Database design](docs/DATABASE_DESIGN.md) |
| REST, SSE, and STOMP conventions | [API specification](docs/API_SPECIFICATION.md) |
| Tutor, document learning, and adaptive workflows | [AI design](docs/AI_DESIGN.md) |
| Navigation, screens, and interaction states | [UI design](docs/UI_DESIGN.md) |
| Phase dependencies and approval checklist | [Roadmap](docs/ROADMAP.md) |
| Proposals and phase-owned decisions | [Decision register](docs/decisions/README.md) |
| Instructions for contributors and coding agents | [AGENTS.md](AGENTS.md) |

## Native production release

Use the [production runbook](docs/PRODUCTION_RUNBOOK.md) to build and verify the release bundle, configure the Linux host, provision administrators, and rehearse backup and restore.

## Optional API discovery

When an approved phase needs an external API, consult the Public APIs directory at <https://github.com/public-apis/public-apis>. Verify pricing, quotas, authentication, and usage terms in the provider's own documentation before integrating it.

## Run locally

Use Java 21, Maven, Node.js 22.13+ on the 22.x line or Node.js 24+, pnpm 11.25.0, and a native MySQL 8+ installation. CI uses Node 24.

1. Start MySQL and run the following statements as a MySQL administrator. Save the generated password for the next step.

   ```sql
   CREATE DATABASE studysphere CHARACTER SET utf8mb4;
   CREATE USER 'studysphere'@'127.0.0.1' IDENTIFIED BY RANDOM PASSWORD;
   GRANT ALL PRIVILEGES ON studysphere.* TO 'studysphere'@'127.0.0.1';
   ```

2. Copy `.env.example` to `.env` and set `STUDYSPHERE_DB_PASSWORD`. If the password contains characters special to Java properties, escape them using Java properties syntax or set it as a process environment variable.
3. Start the backend from the repository root with `mvn -f backend/pom.xml spring-boot:run`.
4. In `frontend`, run `pnpm install --frozen-lockfile` and `pnpm dev`. Open the local URL printed by Vite. The `/status` page shows backend health using Vite's development proxy.

The backend health route is `GET /actuator/health` on port 8080. MySQL defaults to loopback port 3306. Flyway creates its schema history and applies `V1__baseline.sql` on startup.

## Run checks

Document intelligence uses a native or hosted Qdrant endpoint and an embedding provider. Set `STUDYSPHERE_QDRANT_URL` and `STUDYSPHERE_EMBEDDING_PROVIDER=openai`, and configure the backend OpenAI key and a chat provider. Keep Qdrant private and use a separate collection when changing the embedding model. The vector index holds numerical embeddings and document identifiers. MySQL checks access and supplies citation excerpts.

Study Tools starts with chat model calls disabled. Set `STUDYSPHERE_AI_PROVIDER=openai` or `google-genai` and the corresponding key from `.env.example` to enable Mentor replies, practice generation, and plan proposals. Gemini defaults to `gemini-3.5-flash-lite`. Restart the backend from the repository root after changing `.env`. Keep provider keys in the backend environment. Local integration tests use deterministic providers and make no paid model calls.

The resource library stores bytes privately under `./var/resources` by default. Use `STUDYSPHERE_STORAGE_PROVIDER=s3` with a private bucket, region, and optional compatible HTTPS endpoint to use object storage. Supply credentials through the AWS SDK credential chain and scope their permissions to resource objects. Keep public access blocked. `.env.example` lists the storage settings.

For each backend integration run, provision a dedicated empty MySQL schema and a user with privileges on that schema. Set `STUDYSPHERE_TEST_DB_URL` to that schema's JDBC URL and supply credentials through `STUDYSPHERE_TEST_DB_USER` and `STUDYSPHERE_TEST_DB_PASSWORD`. Run `mvn -B -ntp -f backend/pom.xml verify`. The test refuses a nonempty schema.

On Windows, the following script can provision a temporary native instance, run the backend checks, and then clean up. Substitute your MySQL installation path.

```powershell
.\backend\scripts\verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0'
```

In `frontend`, run `pnpm lint`, `pnpm test`, and `pnpm build` after installing dependencies.

## Test account flows locally

Run a native SMTP inbox bound to loopback. The default SMTP address is `127.0.0.1:1025`. Set `STUDYSPHERE_FRONTEND_ORIGIN` to the browser origin printed by Vite so the email links open the same frontend. For loopback HTTP development only, set `STUDYSPHERE_AUTH_SECURE_COOKIES=false`. Enable Secure cookies for HTTPS environments. [Mailpit native installation](https://mailpit.axllent.org/docs/install/)

Open `/register` and verify the account using the delivered link. Sign in with the verified email and use `/forgot-password` to request a reset link. The Maven integration tests use an in-memory email recipient to exercise the delivered links without an SMTP server.

With Mailpit, open the local inbox at `http://127.0.0.1:8025` and select **Verify your StudySphere email**. Mailpit captures development messages locally. If you registered while SMTP was offline, submit registration again with the same email and password after restarting the inbox to request a fresh link. For delivery to real email addresses, configure the backend SMTP host, port, credentials, TLS, and verified sender using the variables in `.env.example`.
