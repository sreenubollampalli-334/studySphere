# AI and learning design

Source [blueprint pp. 7, 15–16 and 26–30](../StudySphere_Codex_Master_Blueprint.pdf).

| Phase | Workflow |
|---|---|
| 06 Matching | Normalize eligible candidate factors to 0–1. Apply weights of 30% subject, 25% availability, 20% goals, 15% complementary skills, and 10% preferences. Exclude self, blocked and hidden candidates before ranking. Explain contributing factors through a privacy-safe projection. |
| 08 Tutor | Use Spring AI ChatClient, versioned prompts, conversation memory and advisors through a configurable Gemini/OpenAI adapter. Stream via SSE. Persist authorized history and usage metadata. Limit request volume, context, output, execution time, and costs. Cancel upstream work when the stream is cancelled. |
| 09 Document learning | Validate PDF/TXT, store privately, extract text and page metadata, chunk, embed and index in Qdrant. Track pending, processing, ready and failed ingestion jobs. Retry idempotently. Filter retrieval by permissions and recheck against MySQL. Return citations tied to real chunk/page metadata and report insufficient sources when retrieval is empty. Clean up vectors on deletion and revocation. |
| 10 Assessments and revision | Generate schema-constrained questions and flashcards. Validate question types and options. Store answer keys server-side, grade objectively in Java, emit topic evidence, and compute due dates deterministically from prior reviews and recall ratings. Persist a review and its next due date atomically. |
| 11 Recommendations and scheduling | Rank recommendations from user-scoped evidence and explain the contributing signals. Use read-only tools to inform proposed schedules. Java validates ownership, time budget and deadlines. Show the preview for explicit approval and create tasks once in a transaction. |
| 12 Room assistant | Apply room access and consent disclosures to the inputs and outputs of room summaries and proposed tasks or quizzes. Preserve scoped visibility and explicit approval of proposed writes. |

Allow only the planned read operations for scheduling tools. The initial tool names are `getUpcomingExams`, `getPendingStudyTasks`, `getWeakTopics`, and `getAvailableStudyHours`. The authenticated server determines scope. Uploaded text, retrieved passages and model output are untrusted input. The server retains write authority. Keep sensitive content out of telemetry and document the selected provider's data handling before enabling it.

Verify with mocked providers and fixtures for timeouts, cancellation, invalid output, isolation, unauthorized retrieval, and fabricated source references. Run live smoke tests with authorized credentials when available. See [security controls](ARCHITECTURE.md) and the [decision register](decisions/README.md).
