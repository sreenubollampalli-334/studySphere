# UI design

The React frontend uses the supplied StudySphere logo mark, the existing product name, and the approved “Learn Smarter. Together.” tagline. The logo mark is cropped from the supplied artwork and used in the header, authentication panel, and favicon.

## Route inventory

| Area | Routes and screens |
|---|---|
| Entry | `/` (authenticated dashboard entry), `/login`, `/register`, `/verify-email`, `/forgot-password`, `/reset-password`, `/account`, unknown-route fallback, and `/status` |
| Workspace | `/dashboard`, `/planner` (plans, tasks, calendar, reminders), `/exams`, and `/focus` |
| Study tools | `/resources` (library, uploads, bookmarks, organizational tools, sharing), `/ai-mentor`, `/documents` and document detail with Q&A, `/quizzes`, `/flashcards`, and `/adaptive` |
| Community | `/partners`, `/messages`, `/rooms` and room detail, `/communities` and community detail |
| Personal | `/onboarding`, `/profile`, `/settings`, `/analytics`, `/achievements`, and `/notifications` |
| Operations | `/admin` (moderation reports, user restrictions, audit log, AI provider usage, and access denied for non-admins) |

## Shared visual system

- Use cool slate surfaces, indigo actions, and the logo orbit on authentication and dashboard feature panels. Keep feature screens centered within the desktop workspace.
- Put the planner at the top of the dashboard and show direct entry points for Focus, Resources, and AI Study Mentor. Keep activity totals tied to the dashboard API.
- Give feature pages a shared title and action area. Use two columns for planner forms, focus settings, and notifications where space permits. Keep messaging history beside the conversation list on wider screens.
- Use card grids for resource, room, and community discovery. Keep the resource upload action visible in the page header and link it to the existing upload form.
- In Personal, pair the focus activity chart with its exact-value table. Distinguish earned badges and unread messages with both text and color. Group profile setup by identity, preferences, and subjects, and give account deletion its own panel.
- Filter onboarding subjects from the loaded catalog as the student types. Keep saved and newly selected subjects checked across searches and show the total selection count.
- Use the tokens in the frontend stylesheet for the page, panels, text, borders, accents, and semantic feedback. The palette follows the operating system’s light or dark preference.
- Use grouped sidebar navigation on desktop and a keyboard-operable disclosure on small screens. Keep the current section visible when navigation is open. Provide a skip link and focus the page heading after navigation.
- Use a single page heading, clear section headings, reusable panels and lists, and consistent primary, secondary, and destructive button treatments. Forms use visible labels, clear boundaries, and responsive field groups.
- Use tinted notices for loading, success, and errors, plus a bordered surface for empty states. Keep existing status announcements and retry actions. Tables scroll within their containers on narrow screens.
- Existing browser confirmation dialogs retain their platform appearance and interaction. Existing data, routes, and permissions drive the interface.

The master blueprint covers the product branding and interaction requirements on pages 2–4 and 17.

## Workflow requirements

- Display calendar times in the selected time zone and restore active focus sessions after refresh.
- Show connection and delivery status in messaging, and show streaming, cancellation, and provider errors in the AI mentor. Continue to render Markdown, code, and math through the existing safe rendering pipeline.
- Show citation provenance for document answers. Preview proposed scheduling changes before approval and distinguish proposals from saved tasks. Preserve room AI disclosures.
