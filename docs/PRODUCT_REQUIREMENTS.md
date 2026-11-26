# Product requirements

**Status** · Draft for Phase 00 approval. Source [blueprint pp. 2–14 and 18–33](../StudySphere_Codex_Master_Blueprint.pdf).

Students use disconnected tools for schedules, explanations, notes, practice, and peer communication. StudySphere supports a single cycle of Plan → Learn → Practice → Analyze → Revise → Collaborate. The first audiences are college students and independent learners. Students are the default account role. Room owners and moderators have scoped permissions. Administrators handle reports and operations. Teacher and mentor features are future scope.

- **R1, MVP (00–08)** delivers the secure study workspace, planner, exams, focus, resources, partner matching, messages, rooms, and AI tutor.
- **R2 (09–14)** adds document learning, quizzes, flashcards, mastery, recommendations, advanced collaboration, engagement, and production hardening.
- **R3 (15)** evaluates optional video, voice, whiteboards, multilingual support, calendars, and mentor features one at a time.
- Initial exclusions include training a custom model, proctoring, university ERP integration, and native mobile applications.

## Module stories and acceptance

| Module and phase | User story and requirements | Acceptance evidence |
|---|---|---|
| M01 Authentication · 02 | As a student, I can register, verify my email, sign in, refresh a session, sign out, reset my password, and delete my account. Google OAuth is later. | Reject invalid credentials and duplicate emails. Revoke reused refresh credentials. Verify protected routes and confirm DTOs exclude hashes. |
| M02 Profiles and onboarding · 03 | I can enter academic details, subjects, skills, goals, language, availability, and privacy preferences. | Check owner-only writes, availability validation, and public projection of approved fields. |
| M03 Dashboard · 03 onward | I can see today’s tasks, upcoming exams, recent resources, rooms, streaks, quiz summaries, and recommendations as they become available. | Confirm user-scoped aggregate reads and honest empty states. |
| M04 AI Study Mentor · 08 | I can explain, simplify, compare, summarize, practice, quiz, interview, ask for hints, and use exam mode. I can stream answers and manage history. | Prove conversation isolation, rate limits, timeout, and cancellation. Check safe Markdown, code, and math rendering. |
| M05 Document learning · 09 | I can upload PDF/TXT, ask about authorized content with citations, and request summaries. Document-derived quizzes and flashcards follow in 10. | Test ACL-filtered retrieval, real page mapping, retries, and vector cleanup. |
| M06 Planner · 04, 11 | I can organize calendars, deadlines, priorities, recurring tasks, subtasks, and reminders. Later I can preview and approve AI schedules or rescheduling. | Check ownership, recurrence, deadlines, time budgets, and retry-safe approval. |
| M07 Focus · 04 | I can customize focus/break durations, tag sessions by subject, and review daily goals and history. Group timers are later. | Refresh restores the session and repeated completion records one result. |
| M08 Partner matching · 06 | I can find ranked partners, understand the match, send requests, and arrange shared study sessions. | Exclude self, blocked users, and hidden profiles. Prevent duplicate requests. |
| M09 Rooms · 07, 12, 15 | I can join public or invited private rooms, chat, view pinned messages and agendas, and share resources. Later phases add notes, summaries, and optional video. | Verify capacity under concurrency and permission on every read, write, and subscription. |
| M10 Private messaging · 06 | I can message accepted connections, page history, see receipts, typing and presence, attach files, and delete or report messages. | Deny nonmembers and block sends between blocked users. Reconnect fills history gaps. |
| M11 Resources · 05 | I can upload, arrange in folders, tag, search, bookmark, collect, share, and revisit resources. AI summaries follow in 09. | Check size/type restrictions, private storage, download access, revocation, and cleanup. |
| M12 Quizzes · 10 | I can practice with generated MCQ and true/false questions, view scores and explanations, and access attempt history. Fill-in and short answer are later. | Validate AI schema, hide answer keys until submission, and score on the server. |
| M13 Flashcards · 10 | I can build or generate decks, rate recall, and review due cards. | Verify deterministic due dates, atomic updates, and duplicate-review protection. |
| M14 Exam center · 04 | I can record dates, syllabus, topics, and goals and see a countdown. Later practice papers and revision notes are learning aids. | Check ownership and date validation. Label AI practice as practice material. |
| M15 Analytics and mastery · 11, 13 | I can see study time, task completion, quiz trends, weak topics, revision and goal progress. | Reproduce calculations and show unknown when evidence is insufficient. |
| M16 Achievements · 13 | I can track XP, levels, streaks, badges and weekly challenges. Optional leaderboards default to private. | Deduplicate rewards and test local date boundaries. |
| M17 Notifications · 13 | I can receive in-app reminders, invitations, message and study alerts, and choose notification preferences. Email/push alerts are later. | Retry scheduled jobs safely and respect preferences. |
| M18 Communities · 12 | I can join subject communities, post, comment, vote, find related questions, and use resource collections and summaries. | Enforce membership and moderation and allow one vote per user/post. |
| M19 Recommendations · 11 | I can receive evidence-based next-topic, revision, resource, quiz, room, or partner suggestions with explanations and provide feedback. | Trace reasons to evidence and keep peer-private data out of explanations. |
| M20 Administration · 14 | As an admin, I can handle reports, restrictions, moderation, and usage/cost monitoring. | Verify admin-only access, audit actions, and limit secret and private-message exposure. |

## Success measures

Establish baselines before setting targets. Track onboarding and task completion, comparable assessment improvement, revision adherence, peer sessions, and qualitative feedback alongside latency, AI cost, and error rates. Evaluate learning using assessment and revision evidence. Track engagement as a separate measure.
