# 0015 · E-mail catalogue and outbox: templates, brand, Reply-To, office address setting, staging guard, bounce handling

* **Status:** Proposed (Phase 1 stage 3 writes the outbox, sender and staging-guard sections; the `notifications` module completes the catalogue, templates and bounce handling in Phase 2 and moves it to Accepted)
* **Date:** 2026-10-23
* **Requirement IDs:** EML-01…EML-09, NFR-04 · **Questions:** Q-07, Q-37 (OP-22 brand «Logistica Sanchez»), Q-43, Q-18 (outbox body retention) · **Findings:** 12c §1.6 defects, L-E11 not ported, SEC-05, SEC-11, SEC-15, SEC-19

## Context

The legacy backend sent e-mail synchronously from the request through SES v1 (a failure
answered 500 and lost nothing but also retried nothing), with an open free-text endpoint
(L-E11, SEC-15), raw e-mail addresses and never-expiring codes in links (SEC-19), HTML-only
bodies without Reply-To (12c §1.6). Chapter 10 §2 "E-mail" asks for an outbox table written
in the business transaction, a worker with retries, encrypted raw tokens for link e-mails,
Reply-To, text and HTML parts, SESv2, and §3 for a staging guard: outside production no
e-mail reaches a real recipient. Phase 1 builds the skeleton; Phase 2 the catalogue.

## Decision

### Outbox (Phase 1)

* **One table, `email_outbox`** (V007, ADR 0020): `kind` (EML-01…EML-09), `recipient`,
  `reply_to`, `subject`, `body_text`, `body_html`, `login_token_id` + `token_ciphertext`,
  `status`, `attempts`, `next_attempt_at`, `last_error`, `sent_at`, `provider_message_id`,
  `bodies_purged_at`.
* **Statuses:** `QUEUED` (waiting for the first attempt or a retry), `SENT`, `FAILED` (retries
  exhausted, or the recipient dropped by the guard: the dead state). The plan's five-state
  list (NEW/SENDING/SENT/FAILED/DEAD) collapses to these three on purpose: one worker instance
  processes one row per transaction, so no `SENDING` lock is needed, and a transient failure
  is a `QUEUED` row with `attempts > 0` and a later `next_attempt_at`, so `FAILED` means dead.
  Simpler is better under Q-55 (the owner asked not to over-complicate); a second worker
  instance would add `SENDING` with `SELECT … FOR UPDATE SKIP LOCKED` behind the same
  service.
* **`NotificationService.enqueue(EmailMessage)`** writes the row **in the caller's
  transaction** and returns its id; nothing sends e-mail from a controller or service. The
  message carries the catalogue kind, recipient, subject, both bodies, optional Reply-To and,
  for one-time links, the `login_token` id and the raw token. The bodies hold the
  placeholder `{{token}}`; the raw token is stored **only encrypted** (AES-256-GCM,
  `TokenCipher`, key `LS_TOKEN_ENCRYPTION_KEY` = base64 of 32 bytes; required in staging and
  production) and never appears in a log, a screen or a body at rest.
* **`OutboxWorker`** (`@Scheduled` fixed delay `ls.notifications.worker.interval`, default
  10 s; off in tests) takes a batch of due `QUEUED` rows (`batch-size` 20), one transaction
  per row: checks the recipient policy, decrypts and substitutes the token, sends through the
  `MailSender` port, marks `SENT` and **wipes the ciphertext and the token reference**; on a
  failure it schedules the retry with exponential backoff (`backoff-base` 30 s × 2^attempt,
  capped at `backoff-max` 1 h) and marks `FAILED` after `max-attempts` (8). A failing row never
  touches another row or the business action that queued it (the row is already committed
  with it). The `login_token` retention job skips rows an undelivered e-mail still references.
* **Retention (Q-18):** bodies and ciphertext are nulled 30 days after delivery
  (`ls.retention.outbox-bodies-days`), metadata kept; the job is the module's (Phase 2) on
  the `platform` scheduler.

### Senders (Phase 1)

`MailSender` is the port; `ls.notifications.sink` picks the adapter:

| Sink | Adapter | Where |
|---|---|---|
| `ses` | `SesV2MailSender` — SESv2 `SendEmail`, region `ls.notifications.ses.region` (eu-north-1), credentials from the environment (the dedicated IAM user with the `ses:FromAddress` condition, ADR 0001), AWS SDK v2 BOM 2.56.0 with the JDK HTTP client only | `prod`; `staging` with an allow-list. No test builds the client. |
| `directory` | `DirectoryMailSender` — one RFC 5322 `.eml` file per delivery under `ls.notifications.directory.path` (`.local/mail`, gitignored), text + HTML as `multipart/alternative` | `local` (default), `staging` without SES. This is the local "mail catcher"; an SMTP catcher adapter is added by the module only if the owner's staging box runs one. |
| `memory` | `InMemoryMailSender` — keeps what was sent for assertions, can be told to fail | `test` |

`From` is `ls.notifications.from-name <ls.notifications.from-address>`
(`Logistica Sanchez <noreply@logisticasanchez.com>` by default, on the verified domain);
Reply-To is per message and is the office address of ADR 0027 where a person should answer.

### Staging guard (Phase 1, tested)

`RecipientPolicy`: outside the `prod` profile, the SES sink **refuses to start** unless
`ls.notifications.allow-list` (`LS_NOTIFICATIONS_ALLOW_LIST`, comma-separated) is non-empty
(`StagingMailGuardIT`); whenever an allow-list is configured outside production, the worker
**drops** any recipient not on it (`FAILED`, `last_error =
DROPPED_RECIPIENT_NOT_ALLOW_LISTED`) and logs the row id, never the address
(`RecipientAllowListIT`). The `directory` and `memory` sinks start without a list. In
production every recipient is allowed and `RequiredSettingsCheck` insists on the SES sink.

### Catalogue, templates, bounce handling (Phase 2 · notifications — to be written)

EML-01…EML-09 as Thymeleaf text + HTML templates under `templates/notifications/`, Italian
«tu», brand «Logistica Sanchez» (Q-37), legacy wording kept without the 12c §1.6 defects,
EML-02/EML-04 behind `ls.notifications.customer-confirmations.enabled` (Q-07), recipients
from the record never from a request field (SEC-05, SEC-14), URL-encoded links on
`ls.business.site-url`; SES bounces and complaints via SNS → SQS polled by the worker (owner
adds the component), a marked address shown to staff; L-E11 not ported. The module appends
these sections and turns the status to Accepted.

## Consequences

* A controller that needs to send e-mail calls a domain service, which enqueues; the test of
  the business action can assert the outbox row and roll back without SES.
* The worker is the single place where SES is called, so SES credentials, retries and the
  guard have one owner; an SES outage delays e-mail, it never loses an order.
* Staging cannot e-mail a real customer by configuration mistake: either nothing leaves the
  machine (directory) or only the owner's listed addresses receive mail.

## References

Chapter 10 §2 "E-mail", §3 "Staging e-mail"; chapter 09 EML-01…EML-09; 12c §1, §5;
`docs/plan.md` §4.1 notifications row, §4.3 "Outbox", §5 Phase 1 item 9; ADR 0001 (SESv2
pin), ADR 0020 (table), ADR 0027 (office address), ADR 0024 (retention).
