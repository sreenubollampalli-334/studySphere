# Development roadmap

Source [blueprint pp. 18–35](../StudySphere_Codex_Master_Blueprint.pdf). Each phase depends on its predecessor. Phase 00 has no dependency. The developer approved Phase 01 and directed continuation after verified phase completion. Report each completed phase and the next deliverable. Phase 15 still requires selection of an optional extension.

## Progress and what each phase unlocks

**Completed** · 15 of 16. **Remaining** · 1 optional phase.

| Phase | Status | What completion unlocks |
|---|---|---|
| 00 | Completed | A reviewed implementation blueprint and agreed foundation scope. |
| 01 | Completed | A runnable foundation for building account access. |
| 02 | Completed | Verified student accounts for private profiles and onboarding. |
| 03 | Completed | A personal workspace ready for real study data. |
| 04 | Completed | A daily study routine with schedules, exams, and focus history. |
| 05 | Completed | A private library of study material to share and later ask questions about. |
| 06 | Completed | Trusted peer connections and private messaging for group collaboration. |
| 07 | Completed | Invitation-based group study alongside the personal workspace. |
| 08 | Completed | A usable R1 release with an AI tutor and the basis for document learning. |
| 09 | Completed | An authorized, cited document corpus for generating practice material. |
| 10 | Completed | Assessment and review evidence for personalized recommendations. |
| 11 | Completed | Personalized study guidance and student-approved schedule changes. |
| 12 | Completed | Course communities and AI-assisted group study. |
| 13 | Completed | Progress indicators and reminders backed by learning activity. |
| 14 | Completed | A deployable R2 release with moderation and operational procedures. |
| 15 | Optional · pending | A selected extension with its own cost, privacy and readiness checks. |

| Phase | Release | Deliverable | Acceptance |
|---|---|---|---|
| 00 Product planning | R1 | PRD, AGENTS.md, architecture and threat model, ERD, API contracts, design docs, ADRs and roadmap | Scope, prerequisites, security decisions, and approval checklist reviewed. Resolve assumptions that block Phase 01. |
| 01 Initialization | R1 | Monorepo, Java/Maven and React/Vite scaffold, native MySQL setup, health, baseline migration, CI, test harness | Both apps boot. Run backend tests, frontend lint/build, fresh DB migration and secret check. |
| 02 Authentication | R1 | Auth APIs, migrations, forms, protected routes, token lifecycle documentation | Duplicate email, invalid credentials, expiry, reused tokens, authorization and reset expiry tested. |
| 03 Onboarding and dashboard | R1 | Profiles, academics, availability, privacy and dashboard shell | Test owner isolation, availability validation, onboarding completion and empty data. |
| 04 Study productivity | R1 | Exams, tasks, planner calendar, recurrence, focus and reminder records | Test dates, ownership, task completion, recurring rules, refresh recovery and duplicate completion. |
| 05 Resources | R1 | Private file storage, metadata, permissions, upload/download, folders, tags and collections | Test invalid files, unauthorized downloads, revocation, deletion and failed upload cleanup. |
| 06 Networking | R1 | Partner matching, connections, blocks and private STOMP messaging | Test ranking, blocked users, duplicate requests, nonmember access and reconnect. |
| 07 Study rooms | R1 | Rooms, invitations, membership, capacity, group chat, agendas and resource sharing | Test concurrent capacity, private access, unauthorized subscriptions and moderation. |
| 08 AI mentor | R1 | Provider configuration, streaming, modes, history, usage and error handling | Test isolation, timeout, cancellation, rate limits and prompt-injection boundaries. Smoke test real provider if a key is available. |
| 09 Document intelligence | R2 | Qdrant, ingestion jobs, extraction/chunking, retrieval, citations, retry and deletion | Test cross-user denial, source mapping, empty retrieval, ingestion failure and vector cleanup. |
| 10 Assessments and revision | R2 | Quizzes, history, flashcards, review scheduler and evidence events | Test malformed model output, hidden answers, deterministic grading, duplicate submission and due dates. |
| 11 Adaptive learning | R2 | Mastery and recommendation APIs, plan proposals and approval UI | Test insufficient evidence, reproducibility, time budgets, idempotent approval and isolation. |
| 12 Advanced collaboration | R2 | Shared notes, room assistant, course communities and moderation | Test consent/disclosure, room ACLs, proposal approval, vote uniqueness and moderation. |
| 13 Engagement and analytics | R2 | Achievements, XP, notifications, scheduled reminders and real analytics | Test duplicate rewards, local date boundaries, retry-safe delivery and accurate aggregates. |
| 14 Production readiness | R2 | Admin, audit, monitoring, deployment, backups, restore runbook and CI/CD | Full suite, E2E, permission matrix, migration/restore rehearsal, secret scan and load smoke test. Resolve critical defects. |
| 15 Optional extensions | R3 | One explicitly approved extension with its own ADR, requirements, privacy and cost review | Verify accessibility, consent, security, failure handling and regressions. |

## Phase 00 review checklist

- [x] Translate the blueprint into Markdown, including MVP stories and module-to-phase traceability. [PRD](PRODUCT_REQUIREMENTS.md)
- [x] Record module dependencies, system boundaries, threat controls, and verification phases. [Architecture](ARCHITECTURE.md)
- [x] Specify the early ERD, ownership and constraints, plus REST and transport conventions. [Database](DATABASE_DESIGN.md) · [API](API_SPECIFICATION.md)
- [x] Record proposed architecture decisions and decision deadlines. [Decisions](decisions/README.md)
- [x] Publish repository instructions and phase-gated verification rules. [AGENTS.md](../AGENTS.md)
- [x] Developer approved the transition from planning to Phase 01.

## Manual review

1. Confirm R1 is the intended MVP and check the R2 and R3 boundaries.
2. Trace refresh rotation, private profile projection, and focus completion through the API, database, and threat model. Check that each has a future acceptance test.
3. Review the decision register for any assumption that would block initialization. Mark approvals explicitly.
4. At each subsequent handoff, review changed files, migration IDs, API changes, commands and results, manual steps, issues, and risks before approving the next phase.

## Phase 01 verification

The foundation adds a local Git repository, the React status page, the Spring Boot service, native MySQL setup, the baseline migration `V1__baseline.sql`, a MySQL integration test, and a GitHub Actions workflow. The only public backend endpoint is `GET /actuator/health`. The developer approved native services in place of the blueprint's Docker Compose and Testcontainers setup.

| Command or check | Observed result |
|---|---|
| `git init -b main` | Succeeded. |
| `git check-ignore .env` and PowerShell secret signature scan of candidate text files | Passed. The password field in `.env.example` is blank. |
| PowerShell XML/JSON parsing of `backend/pom.xml`, `frontend/package.json`, and `frontend/tsconfig.json` | Passed. |
| `mvn -B -ntp -f backend/pom.xml verify` with `JAVA_HOME` set to Java 21 | Maven failed before compilation because it could not resolve Maven Central while fetching the Failsafe plugin. |
| `npm.cmd install --no-audit --no-fund --fetch-retries=0` from `frontend` | npm 10 peer-dependency resolution crashed. Switched to pinned pnpm. |
| `pnpm.cmd install --frozen-lockfile --reporter=append-only --network-concurrency=4 --fetch-retries=2` from `frontend` | Passed. pnpm verified 289 lockfile entries and completed the esbuild postinstall. |
| `pnpm.cmd lint`, `pnpm.cmd test`, and `pnpm.cmd build` from `frontend` | Passed. Two frontend tests passed and Vite produced the production assets. |
| `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port <ephemeral> --strictPort` from `frontend` | Passed. HTTP 200 for the StudySphere page and transformed React entry point. |
| `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with Java 21 | Passed. Maven ran `clean verify` against a fresh schema and both integration tests passed. Flyway applied and validated V1. The packaged service booted against a second fresh schema and `GET /actuator/health` returned UP. Temporary settings used a Maven Central mirror to recover local downloads. |

Phase 01 acceptance passed locally without containers. GitHub Actions is configured and awaits its first hosted run. To check the live proxy manually, run the two apps and confirm that `/status` reports backend availability. [Local setup](../README.md)

## Phase 02 verification


Phase 02 adds `V2__authentication.sql`, cookie and bearer authentication, SMTP-backed account links, and authentication screens. Registration, email verification, sign-in, refresh rotation, logout, password reset, and account deletion are available. The first authentication screens use browser history. Phase 03 can establish the shared React Router navigation shell.

| Command or check | Observed result |
|---|---|
| `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with Java 21 and `MAVEN_ARGS=-o` | Passed. Five integration tests exercised fresh V1/V2 migrations, public health, duplicate email and verification, token replay and isolation, reset expiry and reuse, and deletion reauthentication. The packaged backend booted and returned UP on a second native schema. |
| `pnpm.cmd lint`, `pnpm.cmd test`, and `pnpm.cmd build` from `frontend` | Passed. Twelve tests passed and the production build completed. |
| `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port <ephemeral> --strictPort` from `frontend` | Passed. HTTP 200 for all eight frontend routes and the transformed auth entry. |
| Local secret and Markdown link checks | Passed. The local `.env` is ignored. |

Integration tests capture verification and reset messages in memory. Production SMTP configuration and the first hosted CI run remain rollout tasks. To try the browser flow, sign up, open the verification link from the local inbox, sign in, then exercise sign-out and password reset. [Local SMTP and browser setup](../README.md)

## Phase 00 verification record

At the Phase 00 handoff, the file inventory listed the source PDF and ten new Markdown files. Git reported that the directory was outside a work tree. The link and blueprint checks exited successfully. The following commands and counts describe that snapshot.

<details>
<summary>Commands and results</summary>

~~~powershell
rg --files --hidden -g '!.git'
git rev-parse --is-inside-work-tree
~~~

The inventory returned eleven files. Git exited 128 because this workspace has no Git repository.

~~~powershell
$studyDocs = Get-ChildItem -Path . -Filter *.md -Recurse
$studyLinks = foreach ($studyDoc in $studyDocs) { foreach ($studyMatch in [regex]::Matches((Get-Content -Raw -LiteralPath $studyDoc.FullName), '\]\(([^)]+)\)')) { Join-Path $studyDoc.DirectoryName $studyMatch.Groups[1].Value } }
$studyMissing = @($studyLinks | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) })
if ($studyDocs.Count -ne 10 -or $studyMissing.Count) { $studyMissing; throw 'Unexpected Markdown count or broken link' }
"Verified $($studyDocs.Count) Markdown files and $(@($studyLinks).Count) relative links"
~~~

Link check result · 10 Markdown files and 29 relative links verified.

The following command uses the bundled Python interpreter with pypdf. Set the interpreter path to the local equivalent when repeating the check elsewhere.

~~~powershell
$studyPy = 'C:\Program Files\WindowsApps\OpenAI.CodexPrimaryRuntime.v26-1123-915-0_26.1123.915.0_x64__3k8sg7r9htsxt\dependencies\python\python.exe'
@'
from pathlib import Path
import re
from pypdf import PdfReader

def read(name):
    return (Path("docs") / name).read_text(encoding="utf-8")

pdf = PdfReader("StudySphere_Codex_Master_Blueprint.pdf")
assert len(pdf.pages) == 35
pages = [page.extract_text() for page in pdf.pages]
assert re.findall(r"^M\d{2}(?= )", "\n".join(pages), re.M) == re.findall(r"^\| (M\d{2})(?= )", read("PRODUCT_REQUIREMENTS.md"), re.M)
assert re.findall(r"Phase (\d{2}) [—-]", "\n".join(pages[17:33])) == re.findall(r"^\| (\d{2})(?= )", read("ROADMAP.md"), re.M)
design = read("DATABASE_DESIGN.md")
diagram = design.split("```mermaid", 1)[1].split("```", 1)[0]
entities = set(re.findall(r"\b[A-Z][A-Z_]+\b", diagram))
tables = {name.upper() for row in design.splitlines() if row.startswith("| `") for name in re.findall(r"`([a-z_]+)`", row.split("|")[1])}
assert entities == tables, (entities - tables, tables - entities)
api = read("API_SPECIFICATION.md")
assert all(path in api for path in ["/actuator/health", "/auth/refresh", "/me/profile", "/dashboard", "/focus/sessions/{id}/complete"])
assert all(doc.count("```") % 2 == 0 for doc in map(read, ["ARCHITECTURE.md", "DATABASE_DESIGN.md", "API_SPECIFICATION.md"]))
assert len(list(Path(".").rglob("*"))) == len(list(Path(".").rglob("*.md"))) + len(list(Path(".").rglob("*.pdf"))) + 2
print(f"Verified 35 source pages, 20 module IDs, 16 phase IDs, {len(entities)} ERD tables, and early API paths.")
'@ | & $studyPy -
~~~

Blueprint check result · 35 source pages, 20 module IDs, 16 phase IDs, 20 ERD tables, and early API paths verified. The ERD check compares its entities to the data dictionary. Rendering the diagram is part of the manual review.

</details>

## Phase 03 verification

V3 adds private student profiles, learning preferences, availability, academic catalogs and selections, and learning goals. The authenticated workspace exposes onboarding progress and honest empty dashboard results.

- Ran native MySQL verification with `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` and `MAVEN_ARGS=-o`. All six integration tests passed. Flyway applied V1–V3, and the packaged backend returned UP on a second fresh schema.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/vitest/vitest.mjs run`, `node node_modules/typescript/bin/tsc --noEmit`, and `node node_modules/vite/bin/vite.js build`. All passed, including 15 frontend tests with mocked API responses.
- For manual verification, sign in, open the dashboard, and complete onboarding with a display name, timezone, and one subject. Use Settings to save weekly availability, then create and delete a study goal. Availability currently accepts same-day intervals.

## Phase 04 verification

Migrations `V4__study_planner.sql`, `V5__exams_and_focus.sql`, and `V6__daily_focus_goal.sql` add plans, tasks, reminders, exams, focus history, and daily focus goals. The authenticated dashboard now loads today's tasks and upcoming exams in the student's timezone.

- Ran `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` using Java 21 and `MAVEN_ARGS=-o`. Two unit tests and eight integration tests passed. Flyway applied V1–V6 on a fresh schema, and the packaged backend returned UP.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/typescript/bin/tsc --noEmit`, `node node_modules/vitest/vitest.mjs run`, and `node node_modules/vite/bin/vite.js build`. All passed. Nineteen frontend tests used mocked APIs.
- Manual browser check. Sign in and add a recurring task, an exam, and a daily focus goal. Start a focus session, reload, complete it, and review its history. Check the dashboard for today's tasks and upcoming exams. Phase 04 stores reminder records. Delivery belongs to Phase 13.

## Phase 05 verification

Migration `V7__resource_library.sql` adds metadata, folders, collections, tags, bookmarks, access grants, recent access, and durable object cleanup. The dashboard reads the member's authorized recent files.

- Ran `mvn -s <temporary-settings> -B -ntp -f backend/pom.xml dependency:resolve`, then `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with `MAVEN_ARGS=-o` and temporary filesystem storage. Five unit tests and nine integration tests passed. V1–V7 applied to fresh native MySQL, and the packaged backend returned UP.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/typescript/bin/tsc --noEmit`, `node node_modules/vitest/vitest.mjs run`, and `node node_modules/vite/bin/vite.js build`. All passed. The 21 frontend tests used mocked HTTP responses.
- Manual check. Upload a TXT file, place it in a folder or collection, and share it with a second account. Download from that account, revoke sharing, and confirm the second account loses access. The local filesystem path was verified. Live S3 bucket verification remains a rollout step.

## Phase 06 verification

Migrations `V8__partner_connections.sql` and `V9__private_messaging.sql` add ranked discovery, connection requests and blocks, private conversations, read receipts, attachment metadata, and reports. Partners can arrange study sessions through messages. Attachments accept UTF-8 TXT up to 5 MiB.

- Ran `mvn -s <temporary-settings> -B -ntp -f backend/pom.xml dependency:resolve`, then `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with `MAVEN_ARGS=-o`. Seven unit tests and ten integration tests passed. Fresh native MySQL applied V1–V9 and the packaged backend returned UP.
- Ranking and STOMP interceptor tests covered privacy, subscriptions and revoked credentials. HTTP integration covered discovery, duplicate requests, blocks, message persistence, and catch-up by cursor.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/typescript/bin/tsc --noEmit`, `node node_modules/vitest/vitest.mjs run`, and `node node_modules/vite/bin/vite.js build`. All passed. The 23 frontend tests used mocked HTTP and STOMP.
- Manual browser check. With two accounts, make one discoverable, accept a connection, and exchange messages. Reconnect a tab and check for missed messages. Then block the peer and confirm direct chat is unavailable. Browser-to-broker delivery remains a manual check.

## Phase 07 verification

Migrations `V10__study_rooms.sql` and `V11__room_chat.sql` add rooms, membership and roles, invitations, agendas, resource links, persistent chat, and pins. The dashboard shows rooms the caller belongs to.

- Ran `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with `MAVEN_ARGS=-o`. Seven unit tests and eleven integration tests passed. Flyway applied V1–V11 on fresh native MySQL, and the packaged backend returned UP. The room integration test verified concurrent capacity, private history denial, moderator pins, a live STOMP CONNECT, and rejection of a nonmember's private subscription.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/typescript/bin/tsc --noEmit`, `node node_modules/vitest/vitest.mjs run --maxWorkers=2`, and `node node_modules/vite/bin/vite.js build`. All passed with 26 frontend tests using mocked HTTP and STOMP. One existing test timed out on the first unconstrained run and passed in the bounded rerun.
- Manual browser check. Create a private room, invite a second account, and join from that account. Exchange messages, pin one as owner, then remove the second account and check that room access is revoked. Successful browser-to-browser live delivery can be checked in this flow.

## Phase 08 verification

Migration `V12__ai_mentor.sql` adds owner-scoped conversations, turns, and usage. Spring AI adapters support OpenAI and Gemini. The provider defaults to disabled.

- Ran `mvn -s <temporary-settings> -B -ntp -f backend/pom.xml dependency:resolve` and `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with `MAVEN_ARGS=-o`. Eleven unit tests and twelve integration tests passed. V1–V12 applied to fresh native MySQL, and the packaged backend returned UP. Mentor tests covered isolation, live SSE response and persistence with a deterministic provider, provider cancellation, timeout, prompt instructions, and throttling.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/typescript/bin/tsc --noEmit`, `node node_modules/vitest/vitest.mjs run --maxWorkers=2`, and `node node_modules/vite/bin/vite.js build`. All passed, including 29 frontend tests with mocked responses.
- Manual check. Configure a provider using the README, open the mentor, stream an explanation, stop another response, and reload the conversation history. A live provider smoke test remains pending credentials.

## Phase 09 verification

Migration `V13__document_intelligence.sql` adds the owner-scoped document index, source chunks, and durable ingestion jobs. The document engine uses PDFBox, Spring AI and Qdrant. Citations come from the authorized MySQL chunks. Revoking resource access takes effect immediately through the live authorization check on the existing document index.

- A focused rerun with `MAVEN_ARGS='-o -Dit.test=FoundationIT#documentLifecycleEnforcesOwnershipAndReturnsCanonicalCitations'` also passed simulated indexing failure and retry, empty retrieval, and deletion cleanup using the deterministic vector fixture.

- Ran `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with `MAVEN_ARGS=-o`. Thirteen unit and thirteen integration tests passed, including page numbers, malformed TXT, document access isolation, ingestion, canonical citations and removal from the API. Flyway applied V1–V13 to fresh native MySQL. The packaged backend returned UP. The document HTTP test used a deterministic engine.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/typescript/bin/tsc --noEmit`, `node node_modules/vitest/vitest.mjs run --maxWorkers=2 --testTimeout=20000`, and `node node_modules/vite/bin/vite.js build`. All passed with 31 tests.
- Manual provider check. Follow the README to configure Qdrant and providers. Upload a PDF or TXT file, index it, ask a question, inspect citations, and delete the document. Live Qdrant and provider behavior were outside the deterministic local test.

## Phase 10 verification

V14 adds quizzes and attempt evidence. V15 adds flashcards, ratings, and review scheduling. The native MySQL verifier ran with MAVEN_ARGS=-o, applied V1–V15, passed 16 unit and 15 integration tests, and booted the packaged service. Frontend ESLint, tsc --noEmit, Vitest run --maxWorkers=2 --testTimeout=20000 (33 tests), and Vite build passed.

Manual check. Create a quiz and submit an attempt, then create a deck and record a recall rating. Inspect the history and next due date. Live model generation remains a rollout check.

## Phase 11 verification

V16 adds recommendation feedback. V17 adds saved study-plan previews and approval state. Mastery reads the learner's assessment and revision evidence, and the dashboard shows scoped recommendations.

- The native MySQL verifier ran with `MAVEN_ARGS=-o`. Eighteen unit tests and fifteen integration tests passed in the full run. The new integration test initially asserted the wrong insufficient-evidence label. The focused rerun with `MAVEN_ARGS='-o -Dit.test=FoundationIT#proposalRequiresOwnerApprovalAndDoesNotDuplicateTasks'` passed the corrected test, including ownership, time budget, and retry-safe approval. V1–V17 applied and the packaged service returned UP.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/typescript/bin/tsc --noEmit`, `node node_modules/vitest/vitest.mjs run --maxWorkers=2 --testTimeout=20000`, and `node node_modules/vite/bin/vite.js build`. All passed, including 35 frontend tests.
- Manual check. Open adaptive learning, inspect topic evidence and recommendations, and give feedback. In the planner, generate a preview, inspect its timing, and approve it. Live AI generation remains a provider rollout check.

## Phase 12 verification

V18 adds course communities, members, posts, comments, unique votes and resource links. V19 adds room notes, consent, summaries and explicit proposal approval.

- Ran `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with `MAVEN_ARGS=-o`. All 18 unit and 17 integration tests passed, V1–V19 applied, and the packaged backend returned UP. Integration covered vote uniqueness, moderation, authorized resource download, room ACL, consent, and idempotent approval using a deterministic provider.
- From `frontend`, ran ESLint, TypeScript, `node node_modules/vitest/vitest.mjs run --maxWorkers=2 --testTimeout=20000`, and Vite build. All passed, with 37 tests.
- Manual check. Join a community, post and vote, then consent to room AI processing and approve a suggested task. Live AI generation remains a rollout check.

## Phase 13 verification

V20 adds durable, deduplicated XP and completion timestamps. V21 adds in-app notifications and preferences. Previously completed tasks without timestamps count in lifetime totals, while dated XP and streaks use recorded activity.

- Ran `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with `MAVEN_ARGS=-o`. All 21 unit and 18 integration tests passed, V1–V21 applied, and the packaged backend returned UP. Integration covered reminder retry deduplication, disabled preferences, stable XP and completed-task totals.
- From `frontend`, ran `node node_modules/eslint/bin/eslint.js .`, `node node_modules/typescript/bin/tsc --noEmit`, `node node_modules/vitest/vitest.mjs run --maxWorkers=2 --testTimeout=20000`, and `node node_modules/vite/bin/vite.js build`. All passed, including 40 tests.
- Manual browser check. Complete a task, inspect Analytics and Achievements, schedule a reminder, then mark the delivered item as read. Toggle notification preferences to check suppression. Email and push delivery remain later options.

## Phase 14 verification

`V22__administration.sql` seeds `ADMIN`, migrates private-message report metadata, and adds moderation reports, account restrictions, audit history and a usage index. Community reporting and the role-gated `/admin` endpoints cover report review, account restrictions, audit reads and provider usage. The backend changes are in `admin`, `auth`, `community`, `messaging`, and `config`. Frontend changes add the administration page, account role gating and community reporting. The release workflow, packaging, checks, backup tools and host templates are in `.github/workflows/ci.yml` and `ops/`. The [production runbook](PRODUCTION_RUNBOOK.md) covers deployment, restore, monitoring and operator access.

- Ran `./backend/scripts/verify-native-mysql.ps1 -MySqlHome 'C:\Program Files\MySQL\MySQL Server 8.0' -MavenSettings <temporary-settings>` with Java 21, `MAVEN_ARGS=-o`, and `MAVEN_OPTS=-Daether.syncContext.named.factory=noop`. Maven `clean verify` passed 23 unit tests and 19 native MySQL integration tests. Flyway applied and validated V1–V22, and the packaged backend reported UP. The permission checks exercised anonymous and student denials, administrator access, restriction enforcement and socket token revalidation.
- Ran `pnpm -C frontend lint`, `pnpm -C frontend test`, and `pnpm -C frontend build`. The full suite passed 42 tests, and the focused auth suite passed 12 tests after the final UI adjustments. With the production Vite preview, headless Edge reached sign-in from `/admin`, showed Access denied to a signed-in student, and loaded the console and a live admin API request for a signed-in operator.
- Ran `bash -n ops/data-snapshot.sh`, `bash -n ops/package-release.sh`, `bash ops/package-release.sh /tmp/studysphere-bundle`, and `sha256sum -c studysphere-native.tar.gz.sha256`. The bundle and checksum passed. A native MySQL fixture row and resource file survived snapshot and restore, and restoring into an existing schema was refused. A migrated schema restored into a fresh database started the packaged application and returned UP.
- Ran `node ops/scan-secrets.mjs` after clearing provider key values from `.env.example`. The scanner reported zero matches. Ran `node ops/load-smoke.mjs http://127.0.0.1:8080` against the running package. All 60 requests passed at concurrency 6 with p95 233 ms against a 1,000 ms threshold.
- Manual rollout check. Provision a dedicated administrator, submit a test report from a community member account, resolve it, verify the audit entry, apply and lift a temporary restriction, and confirm the old session stays invalid. Hosted CI, Caddy and systemd activation, live provider credentials, and monitoring destinations remain deployment checks. Usage totals cover mentor streams and community summaries and use configured estimates.
