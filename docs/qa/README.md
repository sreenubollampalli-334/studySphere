# StudySphere QA

The router is implemented in `frontend/src/main.tsx`, `AuthApp.tsx`, and `StudentWorkspace.tsx`. The preview uses native MySQL, filesystem resource storage, and disposable accounts. Application data is isolated from the ordinary development database.

## Route and interaction inventory

| Route | Audience and entry | Main controls and conditional states |
| --- | --- | --- |
| `/` | Anonymous redirects to sign-in. Authenticated users land on Dashboard. Header brand links here. | Session restoration and retry. |
| `/status` | Public direct link. | Health check and retry. |
| `/login` | Public, linked from account entry screens. | Credentials, validation, sign-in, password reset and registration links. |
| `/register` | Public, linked from sign-in. | Account form and private confirmation state. |
| `/forgot-password` | Public, linked from login and reset. | Email form and private confirmation state. |
| `/verify-email` | Public direct link from email. | Missing/invalid token, verification action, success. |
| `/reset-password` | Public direct link from email. | Missing/invalid token, new password, success. |
| Unknown path | Public fallback. | Return to StudySphere. |
| `/account` | Authenticated, sign-in destination and Personal navigation. | Dashboard/setup links, sign-out, delete confirmation, reauthentication and Cancel. |
| `/dashboard` | Authenticated, workspace navigation and root alias. | Setup prompt, planner and tool shortcuts, activity and progress links. |
| `/onboarding` | Authenticated, Personal navigation and dashboard prompt. | Profile/preference forms, subject search and selection, validation, save and redirect. |
| `/profile` | Authenticated, Personal navigation. | Profile fields, discoverability and save. |
| `/settings` | Authenticated, Personal navigation. | Preferences, availability windows, and study goal create/edit/delete. |
| `/planner` | Authenticated, Workspace navigation. | Calendar range/status, plan and task editors, recurrence, reminders, delete confirmations and proposal preview/approval. |
| `/exams` | Authenticated, Workspace navigation. | Exam creation, topic and syllabus fields, edit and delete confirmation. |
| `/focus` | Authenticated, Workspace navigation. | Timer, completion, break, daily goal and history. |
| `/resources` | Authenticated, Study Tools navigation. | Library/bookmarks/recent, search/filter/paging, upload/download, folders/collections, resource editing, sharing/revocation, indexing and delete confirmations. |
| `/documents` and `/documents/:id` | Authenticated. List in Study Tools, detail from list or resource indexing. Owner or shared user sees permitted content. | Status, retries, Q&A with citations, removal confirmation and pagination. |
| `/partners` | Authenticated, Community navigation. | Suggestions, requests, acceptance/rejection, connections, messages and block/unblock. |
| `/messages` and `?conversation=:id` | Authenticated, Community navigation and partner shortcut. Conversation participants only. | Thread selection, attachment/download, send, history pagination, delete/report, socket reconnection. |
| `/rooms` and `/rooms/:id` | Authenticated. List in Community; detail via room/invitation or direct URL. Roles include nonmember, member, moderator and owner. | Creation, join/leave, invitations, members, agenda, resources, chat, assistant consent and room settings. |
| `/communities` and `/communities/:id` | Authenticated. List in Community; detail via discovery or direct URL. Member, owner and moderator actions depend on scoped role. | Create, join/leave, posts, replies, votes, search, reporting/cleanup, summaries, membership and course resources. |
| `/ai-mentor` | Authenticated, Study Tools navigation. Owned conversations. | Conversation history, pagination and management; mode, question, streaming, stop and errors. |
| `/quizzes` | Authenticated, Study Tools navigation. | Topic/document generation, checklist and attempt, scoring and past results. |
| `/flashcards` | Authenticated, Study Tools navigation. | Manual/generated decks, card add/remove, reveal/rate, history and retry. |
| `/adaptive` | Authenticated, Study Tools navigation. | Topic mastery, recommendations and feedback; proposals link to Planner. |
| `/analytics` | Authenticated, Personal navigation. | Activity summary, scrollable daily table and quiz trend. |
| `/achievements` | Authenticated, Personal navigation. | Level, streak, challenge progress and badge states. |
| `/notifications` | Authenticated, Personal navigation. | Inbox, mark read, pagination, refresh and preferences. |
| `/admin` | Admin navigation only. Students get Access denied on direct links. | Moderation reports, restrictions, audit log, provider usage and pagination. |

## Reproduced issues and changes

- At 360 px, open Browse workspace, focus Planner, and press Escape. The baseline menu stayed open. The fix closes it and returns focus to the toggle. Selecting the current item also closes the mobile menu.
- The account deletion confirmation unmounted the focused control after Cancel. It now restores focus, and Escape dismisses the confirmation when idle.
- Shared retry controls can appear inside forms. Their default HTML button type submitted the surrounding form. Retry now uses an explicit non-submit button.
- The daily activity table scroll region now has a keyboard focus target and an accessible name. Narrow screens wrap high-priority header actions and pagination; mobile navigation scrolls independently and desktop navigation stays in view.

## Browser captures

| View | Before | After |
| --- | --- | --- |
| Dashboard, desktop light | [1440 px](before-1440.png) | [1440 px](after-1440.png) |
| Open navigation, phone dark | [360 px](before-360.png) | [360 px](after-360.png) |

## Verified coverage

Pass applies to the interactions listed in each row. The route sweep checks navigation and page rendering, heading focus, current navigation state, and horizontal overflow. It does not certify all operations in that route. Student navigation was checked at 1440, 768, and 360 px. The sweep observed no unhandled page errors or application API 5xx responses.

| Routes | Roles exercised | Result and evidence |
| --- | --- | --- |
| Root, status, login, register, forgot password, verify email, reset password, fallback | Anonymous, newly registered student | **Pass, live**. Redirect, health, public forms, SMTP verification and reset links, rejection of old credentials, sign-in/sign-out. Token expiry, replay and revocation also covered by MySQL integration. |
| Account | Student | **Pass, live**. Open confirmation, Escape and Cancel focus return. Permanent deletion and reauthentication pass in automated fixtures and MySQL integration. |
| Dashboard, onboarding, profile, settings | Student | **Pass, live**. Navigation, onboarding validation, subject search, save, persisted profile and expired-session redirect; availability validation covered by frontend test. |
| Planner and exams | Student | **Pass, live**. Task creation with one POST on double click, exam creation, native confirmation dismissal, and persistence on reload. Recurrence and proposal approval exercised with backend integration fixtures. |
| Focus | Student | **Pass, live**. Timer survives refresh and session completes. Duplicate completion covered by MySQL integration. |
| Resources | Student owner | **Pass, live**. Upload, search, file download and reload. Sharing and permission revocation are covered in frontend and native MySQL integration tests. |
| Documents list and detail | Student owner and student opening a missing direct link | **Pass, live** for queued indexing, failure, retry and missing route states. **Blocked live** for ready-state Q&A, citations and document-based practice. Configured Qdrant is unavailable. Index recovery, authorization and citations pass with the deterministic backend fixtures. |
| Rooms list and detail | Owner, nonmember, member | **Pass, live**. Create public room, owner settings, nonmember join and member access after reload. Capacity and membership boundaries pass in MySQL integration. |
| Partners and Messages | Two student accounts | **Pass, live**. Discovery, request, acceptance, query-string conversation link, send, direct-link retrieval and reload. Duplicate requests and blocked access pass in MySQL integration. |
| Communities list and detail | Owner, nonmember, member | **Pass, live**. Creation, long text wrapping at 360 px, joining and persisted voting. Consent and permissions covered by MySQL integration. |
| AI Mentor | Conversation owner | **Pass, live**. Gemini reply streamed and history restored. Provider timeout, limits and ownership pass in automated tests with fixtures. |
| Quizzes | Student | **Pass, live** for topic generation, grading and reopening the saved score through Attempt history. **Blocked live** for document-based generation. |
| Flashcards | Student | **Pass, live**. Manual deck and review, with due queue checked after reload. Lost-response retries pass in frontend fixtures. |
| Adaptive, analytics, achievements | Student | **Pass, live** for navigation and rendering. Proposal validation/approval and progress calculations pass in backend fixtures. |
| Notifications | Student | **Pass, live**. Preference change persists on reload. Notification deduplication passes in native MySQL integration. |
| Admin | Administrator and student | **Pass, live**. Admin opens Reports, Restrictions, Audit log, and AI usage. Student is denied. Restriction writes and access revocation pass in MySQL integration. |
| Quiz source retry | Student with induced catalog failure | **Pass, browser fixture**. Delayed 503 shows loading then error, Retry re-requests the catalog and sends zero quiz-generation requests. |

## Checks and limits

- Frontend commands passed from `frontend`: `pnpm.cmd lint`, `pnpm.cmd test --maxWorkers=2 --testTimeout=30000` (45 tests), `pnpm.cmd build` (TypeScript check and production bundle).
- The native MySQL verification script ran Maven offline with `verify`, skipping `clean` to preserve the existing target directory. It passed 23 unit and 19 integration tests, applied V1–V22, and booted the packaged jar with health UP. No migration or API contract changed.
- Sampled shared text, muted text and primary button foreground/background pairs measured 6.44:1 or higher across both themes. Sampled focus ring and panel pairs measured 6.61:1 or higher. This is a targeted check.
- Production SMTP delivery to real inboxes was outside the local preview. Registration and reset were verified with native Mailpit.
- Untested browser combinations include every moderator operation, live document-based generation, populated pagination across each library, and each destructive action variant. Automated coverage does not establish those browser combinations.
