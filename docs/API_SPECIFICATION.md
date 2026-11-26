# API specification

Health and authentication describe the Phase 01 and 02 interfaces. Later interfaces are drafts for their feature phases. Resource paths are relative to `/api/v1` unless fully qualified. [Blueprint pp. 5–16 and 19–22](../StudySphere_Codex_Master_Blueprint.pdf)

## REST conventions

- Use JSON DTOs, camelCase property names, decimal string identifiers, UTC ISO 8601 timestamps, and ISO calendar dates with an IANA zone for local scheduling. Keep secrets, hashes, internal fields, and answer keys out of inappropriate projections.
- Require a verified, active authenticated principal for protected student routes. Derive owner identity from the principal. Use scoped lookups for private resources. Return `404` for inaccessible resource IDs and `403` when a known operation exceeds the caller's permissions.
- Return `200` for successful reads and edits, `201` with `Location` for creation, and `204` for successful no-body actions. Use `400` for malformed or invalid inputs, `401` for missing/invalid credentials, `403` for denied operations, `404` for unavailable resources, `409` for state conflicts, and `429` with `Retry-After` for throttling. Files can also return `413` or `415`. Use `503` for unavailable dependencies.
- Use `application/problem+json` with `type`, `title`, `status`, stable `code`, and optional field `errors`. Error details exclude confidential values. Collections use zero-based `page` and `size`, default 20 and max 100. Use deterministic order with an ID tiebreaker. Return `items`, `page`, `size`, and `hasNext`. Messaging history can use cursors when the phase contract defines them.
- Use numeric `version` in edits of versioned entities and return `409` on stale writes. Make completion and approval retry-safe using their resource identity. Any endpoint-specific idempotency keys must have a defined caller scope and replay behavior.

```json
{"type":"about:blank","title":"Validation failed","status":400,"code":"VALIDATION_FAILED","errors":[{"field":"endsAt","code":"END_BEFORE_START"}]}
```

## Phase 01 · Health

| Method and path | Request and expected response | Failure |
|---|---|---|
| GET `/actuator/health` | Empty request. `200 {"status":"UP"}`. Publish status only on the public route. | `503 {"status":"DOWN"}` for an unhealthy service. |

## Phase 02 · Authentication

Use opaque bearer access tokens for protected calls. Login and refresh return a bearer token, held in browser memory, and set the host-only `studysphere_refresh` cookie. The refresh cookie is HttpOnly and SameSite=Lax with path `/api/v1/auth`. Secure is enabled by default and has a loopback-only development opt-out. Public auth POSTs require the `X-XSRF-TOKEN` header and matching `XSRF-TOKEN` cookie. Auth responses use `Cache-Control: no-store`.

Access tokens expire in 15 minutes or at the family deadline, whichever comes first. A refresh credential has a seven-day idle lifetime and a 30-day absolute family lifetime. Rotation atomically consumes the predecessor and replaces its access token. A recognized replay revokes the whole family. The frontend coordinates refresh calls to use the current cookie. Logout revokes the presented family, and password reset or account deletion invalidates all account sessions.

| Method and path | Request → outcome |
|---|---|
| GET `/auth/csrf` | `204` and the `XSRF-TOKEN` cookie for subsequent authentication writes. |
| POST `/auth/register` | `{"email":"student@example.test","password":"example-password"}` → `202` with a generic response and verification delivery for eligible accounts. Existing canonical emails never create another account. |
| POST `/auth/verify` | `{"token":"verification-token"}` → `204`. Verification links expire after 24 hours. Invalid, consumed, or expired tokens return `400`. |
| POST `/auth/login` | `{"email":"student@example.test","password":"example-password"}` → `200 {"accessToken":"access-token","expiresInSeconds":900}` plus refresh cookie. Invalid credentials return `401` and unverified accounts with correct credentials return `403`. |
| POST `/auth/refresh` | Refresh cookie + CSRF proof → `200` with a new access token and rotated cookie. Invalid, expired, reused tokens return `401`, and failed CSRF returns `403`. |
| POST `/auth/logout` | Refresh cookie + CSRF proof → `204`, revoke current family when present and clear cookie. |
| POST `/auth/forgot-password` | `{"email":"student@example.test"}` → generic `202` independent of account existence. |
| POST `/auth/reset` | `{"token":"reset-token","newPassword":"example-new-password"}` → `204` and revoke refresh families. Reset links expire after one hour. Invalid, expired and reused tokens return `400`. |
| GET `/me` | Bearer → `200` with decimal string `id`, `email`, and `roles`. Unauthenticated or expired requests return `401`. |
| DELETE `/me` | Bearer from password sign-in within five minutes → `204` and invalidate the account's sessions. Return `403` when a fresh sign-in is required. |

Account creation and password reset accept passwords of 12–128 Unicode code points. Pending accounts can request a replacement verification link by repeating registration with their original password. Verification and reset links carry the token in the frontend URL fragment.

Registration, login, reset requests and password resets share a per-source credential-attempt limit of 60 requests per minute by default. A throttled response returns `429` with `Retry-After`. The development limiter uses the direct peer address. A reverse proxy should enforce its own trusted per-client policy before production rollout.

## Phase 03 · Profile and dashboard

| Method and path | Request → outcome |
|---|---|
| GET, PUT `/me/profile` | GET returns private caller profile. PUT accepts display name, academic details, bio and discoverability. Example `{"displayName":"Asha","institution":"Example College","discoverable":false}` → `200` with saved projection. |
| GET, PUT `/me/preferences` | GET returns and PUT replaces caller preferences such as `{"language":"en","timeZone":"Asia/Kolkata","learningStyles":["practice"],"skills":["SQL"]}` → `200`. |
| GET, PUT `/me/availability` | GET lists slots. PUT replaces with `{"slots":[{"day":"MONDAY","start":"18:00","end":"19:00"}]}` → `200`. Invalid intervals or overlaps return `400`. |
| GET `/subjects`, GET `/subjects/{id}/topics` | Paged catalogs return `200` with items such as `{"id":"7","name":"Databases"}`. |
| GET, PUT `/me/subjects` | List or replace selected subjects, e.g. `{"subjectIds":["7"]}` → `200`. |
| GET, POST `/me/goals` and PATCH, DELETE `/me/goals/{id}` | List, create, edit, or remove a goal. Example `{"subjectId":"7","description":"Practice normalization","targetDate":"2026-12-01"}` → `201` with ID and status. |
| GET `/dashboard` | `200 {"todayTasks":[],"upcomingExams":[],"recentResources":[],"activeRooms":[],"quizSummaries":[],"recommendations":[],"streak":null}` for a new account. Populate from feature projections as they ship. |

The private profile and dashboard expose `onboardingComplete`. Completion requires a display name, an explicitly saved IANA timezone, and at least one selected subject. All Phase 03 routes require a bearer token. Availability accepts nonoverlapping same-day local times in `HH:mm` in the selected timezone. Phase 06 uses an allowlisted public matching projection that omits email, precise availability, and private preferences.

## Phase 04 · Productivity

| Method and path | Request → outcome |
|---|---|
| GET, POST `/plans` and GET, PATCH, DELETE `/plans/{id}` | List, create, retrieve, update, or remove a plan. Example `{"title":"DBMS prep","subjectId":"7"}` → `201` with ID and version. |
| GET, POST `/tasks` and GET, PATCH, DELETE `/tasks/{id}` | List by date range/status, create, retrieve, edit/complete, or delete. Example `{"title":"Review BCNF","planId":"20","dueAt":"2026-12-01T13:00:00Z","priority":"HIGH"}` → `201` with ID, state, and version. Parent and recurrence fields are optional. |
| GET, POST `/tasks/{id}/reminders` and DELETE `/tasks/{id}/reminders/{reminderId}` | List, add, or remove reminder records. Example `{"scheduledAt":"2026-12-01T12:30:00Z"}` → `201`. Duplicate schedule returns `409`. |
| GET, POST `/exams` and GET, PATCH, DELETE `/exams/{id}` | Example create `{"subjectId":"7","title":"DBMS final","examDate":"2026-12-08","timeZone":"Asia/Kolkata","topicIds":["19"]}` → `201` with ID and date. Invalid or mismatched dates/topics return `400`. |
| GET, POST `/focus/sessions` | List focus history with optional state filter. Start with `{"subjectId":"7","focusMinutes":25,"breakMinutes":5}` → `201 {"id":"61","state":"ACTIVE","startedAt":"2026-12-01T13:00:00Z","focusMinutes":25,"breakMinutes":5}`. Active-session conflicts return `409`. |
| GET `/focus/sessions/{id}`, POST `/focus/sessions/{id}/complete` | Retrieve server timestamps and state, or finish with empty body → `200` with completed state and server completion time. A retry returns the recorded completion without another log. |

Phase 04 extends `/me/preferences` with nullable `dailyFocusGoalMinutes` (1–1440). `GET /focus/summary` supplies daily totals in the caller's timezone.

Tasks can repeat DAILY or WEEKLY with a due instant and an IANA recurrence zone. Completion creates the immediately following occurrence in the same transaction and uses the original series wall-clock time. The next occurrence can already be overdue. A daylight-saving gap shifts the occurrence forward by the transition duration, and an overlap uses the earlier offset. Retrying completion of the same occurrence does not create a duplicate.

## Phase 05 · Resource library

Resources use authenticated multipart uploads with a required `file` field and optional `title`, comma-separated `tags`, and `folderId`. PDF, PNG, JPEG, and UTF-8 text are accepted up to 20 MiB. The API only returns downloads after checking current ownership or an explicit share grant. Owners can share by recipient email and revoke by recipient ID. Revoked access also disappears from bookmarks and recent views. Folder and collection operations are scoped to the current member. A collection can hold accessible resources without transferring ownership or extending access.

| Method and path | Purpose |
|---|---|
| GET, POST `/resources` | Search/list accessible resources or upload privately. |
| GET, PATCH, DELETE `/resources/{id}` | Read metadata or update/delete as owner. |
| GET `/resources/{id}/download`, GET `/resources/recent` | Download an authorized file or view recently accessed resources. |
| GET, POST `/resources/{id}/permissions`, DELETE `/resources/{id}/permissions/{userId}` | Inspect, grant, or revoke sharing as owner. |
| GET, POST `/folders`, GET, POST `/collections`, DELETE `/folders/{id}`, DELETE `/collections/{id}` | Organize the current member's library. |
| PUT, DELETE `/collections/{id}/resources/{resourceId}` | Add or remove a collection item. |
| GET `/bookmarks`, PUT, DELETE `/bookmarks/{resourceId}` | List, add or remove the current member's bookmarks. |

## Phase 06 · Partners and private messaging

Partner results expose a public profile, compatibility score, and reasons. They exclude private availability details, emails, hidden profiles, and blocked accounts. The initial score weights subjects at 30%, availability at 25%, goals at 20%, complementary skills at 15%, and learning preferences at 10%.

| Method and path | Purpose |
|---|---|
| GET `/partners`, GET `/connections`, GET `/connections/requests` | Discover partners and read the caller's connections or requests. |
| POST `/connections/requests`, PATCH `/connections/requests/{id}` | Request a connection by user ID and accept, decline, or cancel as authorized. |
| DELETE `/connections/{userId}`, GET `/blocks`, PUT/DELETE `/blocks/{userId}` | Remove a connection or manage the caller's blocks. |
| GET, POST `/conversations`, GET, POST `/conversations/{id}/messages` | List or open conversations and read or send messages. |
| POST `/conversations/{id}/read`, DELETE `/conversations/{id}/messages/{messageId}` | Update read state or delete a message. |
| POST `/conversations/{id}/messages/{messageId}/report`, GET `/conversations/{id}/messages/{messageId}/attachment` | Report a message or download an authorized attachment. |

The WebSocket transport uses `/ws/messages`. The STOMP CONNECT frame supplies the bearer token in `Authorization`. Subscribe to `/user/queue/conversations/{id}`. Send typing updates to `/app/conversations/{id}/typing` and messages to `/app/conversations/{id}/messages`. Sends and subscriptions require an authorized conversation. Clients recover missed messages from REST using `afterId` on reconnect and deduplicate by message ID. Use `beforeId` to page older history.

## Phase 07 · Study rooms

Public rooms can be discovered and joined by signed-in users. Private rooms require an invitation. Room members can read agendas, shared files and chat; room owners and moderators manage invitations, agenda entries and pinned messages. Owner and moderator permissions are scoped to membership in that room. Joining checks capacity under a room lock. Download authorization requires current membership and an active room share.

| Method and path | Purpose |
|---|---|
| GET, POST `/rooms`, GET, PATCH, DELETE `/rooms/{id}` | Discover, create, inspect, or manage rooms. |
| GET `/rooms/{id}/members`, PUT/DELETE `/rooms/{id}/members/me` | List members, join, or leave. |
| PATCH/DELETE `/rooms/{id}/members/{userId}` | Change a member's role or remove a member. |
| GET `/room-invitations`, GET/POST `/rooms/{id}/invitations`, DELETE `/rooms/{id}/invitations/{userId}` | Discover, grant or revoke invitations. |
| GET/POST `/rooms/{id}/agenda`, DELETE `/rooms/{id}/agenda/{itemId}` | Read or manage an agenda. |
| GET/POST `/rooms/{id}/resources`, DELETE `/rooms/{id}/resources/{resourceId}` | List, share or remove room resources. |
| GET `/rooms/{id}/resources/{resourceId}/download` | Download a resource through a current room share. |
| GET/POST `/rooms/{id}/messages`, PUT/DELETE `/rooms/{id}/messages/{messageId}/pin` | Read cursor-paged history, post messages, or moderate pins. |

STOMP reuses `/ws/messages`. Subscribe to `/user/queue/rooms/{id}` and send to `/app/rooms/{id}/messages`. The broker checks current room membership on subscription, send and delivery.

## Phase 08 · AI Study Mentor

| Method and path | Purpose |
|---|---|
| GET, POST `/ai/conversations` | List or create conversations belonging to the caller. |
| GET, PATCH, DELETE `/ai/conversations/{id}` | Read, rename or delete a conversation. |
| GET `/ai/conversations/{id}/messages` | Read the caller's stored conversation turns. |
| POST `/ai/conversations/{id}/stream` | Submit `{message,mode}` and stream an answer using authenticated fetch. |

The SSE response sends `delta` with `{text}`, `complete` with `{message}`, or `error` with `{code,message}`. The server checks ownership before contacting a provider. The client aborts its fetch when the user stops the answer or leaves the conversation.

## Phase 09 · Document intelligence

Index a private PDF or TXT resource with `POST /documents` and `{resourceId}`. Use `GET /documents` to list documents, `GET /documents/{id}/status` to check ingestion, and `POST /documents/{id}/retry` to retry a failure. `DELETE /documents/{id}` removes its index. `POST /documents/{id}/ask` accepts `{question}` and returns an answer with citations containing chunk IDs, page numbers when available, and source excerpts. Retrieval rechecks the source resource's current permissions.

## Phase 10 · Assessments and revision

Assessment endpoints require bearer authentication. Quiz attempts return questions and choices before submission. The server grades MCQ and true/false responses and returns results and explanations after submission. Repeating a successful submission returns its recorded result. Quiz and flashcard generation accepts a topic or an authorized, ready document.

| Method and path | Purpose |
|---|---|
| GET/POST `/quizzes`, POST `/quizzes/generate`, GET `/quizzes/{id}` | List, create, generate or inspect owned quizzes. |
| GET/POST `/quizzes/{id}/attempts`, GET `/attempts/{id}`, POST `/attempts/{id}/submit` | Read attempt history, start, load or submit an attempt. |
| GET/POST `/flashcard-decks`, POST `/flashcard-decks/generate`, GET/DELETE `/flashcard-decks/{id}` | Manage owned decks. |
| POST `/flashcard-decks/{id}/cards`, GET `/flashcards/due`, GET/POST `/flashcards/{id}/reviews` | Add cards, find due cards, read review history or record a recall rating. |

Flashcard review requires an idempotency request ID and one of `AGAIN`, `HARD`, `GOOD` or `EASY`. Review scheduling and grading update evidence in the same transaction as the recorded result.

## Phase 11 · Adaptive learning

| Method and path | Purpose |
|---|---|
| GET `/analytics/topics` | Read topic mastery and its evidence counts. Topics with insufficient evidence report an unknown state. |
| GET `/recommendations`, POST `/recommendations/{id}/feedback` | Read learner-specific recommendations and record feedback. |
| GET/POST `/plans/proposals`, GET `/plans/proposals/{id}` | List, generate, or inspect a preview before it creates tasks. |
| POST `/plans/proposals/{id}/approve` | Validate and save a plan and tasks in one transaction. Retries return the same result. |

Proposal generation accepts `{title,startDate,endDate,minutesPerDay}`. Approval is explicit and scoped to the requester. Recommendations explain the learner's evidence without exposing peer-private details.

## Phase 12 · Advanced collaboration

Community posts, comments, votes, related questions, summaries, and resource collections enforce community membership and moderation. A member has at most one vote per post. Changing a vote replaces the prior value.

Room members can manage shared notes within their role. Use `GET /rooms/{id}/assistant` to read disclosure and consent state and `PUT /rooms/{id}/assistant/consent` to record consent. Summary generation through `POST /rooms/{id}/summaries` requires consent from all current members. Suggested tasks and quizzes only become saved items after explicit approval at `POST /rooms/{id}/assistant/proposals/{proposalId}/approve`.

## Phase 13 · Engagement and analytics

The analytics overview and achievements read the caller's authenticated study activity. Daily streaks and weekly challenges use the learner's selected timezone. Notifications are in-app and honor the caller's preferences. Scheduled delivery deduplicates by the source event, so retries do not create multiple copies.

## Later interfaces

| Phase | Candidate paths and transport |
|---|---|
| 14 | Community and message reporting, `/admin/reports`, `/admin/restrictions`, `/admin/audits`, `/admin/usage`. |

Authenticate SSE before opening the stream. Use an authenticated fetch stream for bearer headers. Propose named `delta`, `complete`, and `error` events and define cancellation and response persistence in Phase 08. Authenticate STOMP sessions, authorize SEND and SUBSCRIBE independently, and revoke access when relationships change. Reconnect with REST history and deduplicate by message ID. Define destinations in Phases 06–07.
## Phase 14 · Administration

Community members can submit a report with a reason via `POST /posts/{id}/report` or `POST /posts/{id}/comments/{commentId}/report`. The existing private-message report route also feeds the moderation queue. Reported private messages expose only moderation metadata in the queue.

All routes under `/admin` require the `ADMIN` role. These routes use bearer authentication.

| Method and path | Purpose |
|---|---|
| GET `/admin/reports` | List moderation reports. |
| PATCH `/admin/reports/{kind}/{targetId}/{reporterId}` | Record a moderation decision and its audit trail. |
| GET `/admin/restrictions` | List account restrictions. |
| PUT `/admin/restrictions/{userId}` | Restrict an account with a reason and optional `expiresAt` UTC instant. Existing credentials are revoked. |
| DELETE `/admin/restrictions/{userId}` | Lift a restriction with a JSON body containing the reason. |
| GET `/admin/audits` | Read administrative action history. |
| GET `/admin/usage` | Read aggregate AI provider activity and configured cost estimates. |

Restricted accounts cannot log in, refresh credentials, or use authenticated HTTP or socket routes. After a restriction expires or is lifted, the user can sign in again.

Usage totals cover mentor streams and community summaries. Cost estimates use configured per-million-character rates. Document, quiz, plan, and room-assistant provider calls have separate paths and are outside these totals.
