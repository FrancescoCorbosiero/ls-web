# Architecture decision records

One file per decision, numbered `NNNN-<slug>.md`, never renumbered. A superseded ADR keeps
its file and gets the status `Superseded by NNNN`. Every deliberate change from the legacy
behaviour is recorded here (chapter 01 principle 1, `PROMPT.md` rule 1); an owner answer is
written in the "Decision" column of `docs/brief/11-open-questions.md` and in the affected
ADR in the same commit (`PROMPT.md` rule 7). Later sessions take decisions only from
chapter 11 and this folder, never from the chat.

## Index

| ADR | Title | Status | Date | Notes |
|---|---|---|---|---|
| [0001](0001-path-and-stack.md) | Path and stack | Proposed | 2026-10-17 | Implemented from Phase 1 on the owner's go-ahead of 2026-10-17 («puoi procedere in autonomia»), which is not an answer to Q-01/Q-52: confirmation of its Gate 0 items 1–4 still pending |

## Template

```markdown
# NNNN · Title

* **Status:** Proposed | Accepted | Superseded by NNNN
* **Date:** YYYY-MM-DD
* **Requirement IDs:** PUB-01, … (chapter 09) · **Questions:** Q-xx (chapter 11) · **Findings:** OP-/CMS-/LPS-/SEC-/DQ-/F-…

## Context

What the legacy does today (cite `repo@ref:path:line`), what the brief asks, what is
ambiguous or broken, and why a decision is needed now.

## Decision

The rule the new app follows, stated so that a test can check it. Name the seam
(setting or method with its `Q-xx` comment) for every default the owner may still change.

## Consequences

What becomes easier or harder; behaviour that changes for customers or the office; data
migrated differently; what the owner must confirm and at which gate.

## References

Brief sections, legacy citations, reader notes (`docs/phase0/readers/`), related ADRs.
```

## Planned ADRs

The Phase 0 readers collected the deliberate fixes below. Each is written by the phase and
module that implements it (Phase 1 = integrator; Phase 2 = the owning module of
`docs/plan.md` §4), with the number reserved here. A planned ADR may be split or merged
when written; the IDs it covers must stay covered.

| ADR | Title | IDs and findings covered | Written in |
|---|---|---|---|
| 0002 | Deny-by-default authorisation, route × role matrix and HTTP error model | NFR-02, ADM-01, API-03; SEC-01, SEC-02, SEC-04, SEC-09; Q-21 (what not to reproduce) | Phase 1 |
| 0003 | Staff authentication, sessions, roles and account provisioning | ADM-01; Q-17; CMS-01…03, SEC-08, SEC-10; session lifetimes of 10 §2 | Phase 1 |
| 0004 | Customer sign-in by one-time link consumed by `POST`; identity from the session | CUS-01, EML-01, EML-06; Q-11, Q-30; OP-S1, OP-S2, SEC-11, SEC-12, SEC-19 | Phase 1 |
| 0005 | Customer lifecycle, registration rules and declined customers | CUS-02, CUS-03, CUS-06, ADM-02; Q-09, Q-10, Q-28, Q-36; OP-08…OP-11, OP-S4 (abuse protection on registration), CMS-20, DQ-25 | Phase 2 · customers |
| 0006 | Order wizard: lines, totals, temporary codes, idempotent submission; wizard redesigned on the new site's design system; changes to a sent order only by asking the office | CUS-04, CUS-05; Q-12, Q-13, Q-24, Q-35; OP-01…OP-07, OP-12…OP-18, OP-20, OP-23, OP-25, DQ-27, DQ-28 | Phase 2 · orders |
| 0007 | Order approval contract: codes, amount, VAT flag, state machine, decline | ADM-04, EML-05; Q-05, Q-06; CMS-13, CMS-14, CMS-21, SEC-14, DQ-13, DQ-29, DQ-31, DQ-32 | Phase 2 · orders |
| 0008 | Tracking history as events; transitions on approved orders; EML-09 once per transition | ADM-06, EML-09, ENH-05; Q-04; CMS-08, CMS-10, CMS-12, DQ-30; 12c §7 "add EML-09" is stale — EML-09 is already in M1 (D-96, chapter 09 correction) | Phase 2 · tracking |
| 0009 | Internal notes separate from the customer's note; import rule for the legacy note | ADM-07; Q-26; CMS-09, CMS-11, DQ-18 | Phase 2 · orders |
| 0010 | Compatibility API: tracking contract, code normalisation, CORS, rate limit, public payload | API-01, API-02, TRK-02, TRK-03, TRK-04; Q-03, Q-38, Q-39; CU-01, CU-02, CU-14, CU-17, SEC-18, LPS-S2, LPS-S3; F-15 (no CORS credentials), F-16 (no plain-HTTP origins) | Phase 2 · tracking |
| 0011 | Retired legacy endpoints answer 410; old `/mono/**` links; one app on apex and `app.` | API-03; Q-30, Q-50; SEC-15, SEC-16, CMS-05, F-14 (`EMAIL_ORIGIN` host ambiguity → one base-URL setting, old links on both hosts); the 33 retired mappings of `legacy-ls-api-security.md` §5 | Phase 2 · tracking |
| 0012 | Public site port: allowed HTML differences from ls-home@9b78785, caching, headers, `/404.html`, `tracking.js` on 429/5xx, generated sitemap/robots/security.txt (`lastmod` from a content date, not git) | PUB-01, PUB-03, PUB-05, PUB-09, PUB-10, TRK-01; Q-31; 06 §8.4–8.5, §9 | Phase 2 · site |
| 0013 | Legacy URL redirect map and host rules | PUB-04; Q-29; LPS-06, LPS-U1, LPS-U5 | Phase 2 · site |
| 0014 | Public forms: validation that rejects, CSRF exemption with Origin check, lead storage, outbox hand-off | PUB-06, PUB-07, PUB-11, EML-08; 06 §5.5–5.6 quirks 5–6; OP-S4 (abuse protection on order and form submission: rate limits, honeypot, maximum lengths) | Phase 2 · site |
| 0015 | E-mail catalogue and outbox: templates, brand, Reply-To, office address setting, staging guard, bounce handling | EML-01…EML-09, NFR-04; Q-07, Q-37 (OP-22 brand «Logistica Sanchez»), Q-43; 12c §1.6 defects, L-E11 not ported | Phase 2 · notifications |
| 0016 | Report delivery through authenticated downloads; report semantics (period in Europe/Rome, Dt/Cons., columns) | ADM-09, EML-07; Q-08; CMS-06, CMS-22, SEC-03, SEC-05 | Phase 2 · notifications (reports) |
| 0017 | Back-office lists, pagination, detail presentation and UX conventions | ADM-02, ADM-03, ADM-05, ADM-08; CMS-06, CMS-07, CMS-15…CMS-19, CMS-23…CMS-26 | Phase 2 · backoffice |
| 0018 | Output escaping, CSP without `unsafe-*`, HTMX configuration, input validation | NFR-02, NFR-01; Q-22; CMS-04, CMS-27…CMS-29 (same origin, self-hosted assets, nothing ported from the CMS), OP-S3 (HTTPS only, HSTS on every host), OP-S5, SEC-13; F-15, F-16 (CORS with credentials and plain-HTTP origins not reproduced) | Phase 1 |
| 0019 | Logging without bodies or personal data; configuration from the environment; management endpoints | NFR-02, NFR-03, NFR-04; Q-53; SEC-06, SEC-07, SEC-17, SEC-20, F-02…F-07, F-13, F-14 (configuration names from the environment, one explicit base-URL setting) | Phase 1 |
| 0020 | Data model conventions: PostgreSQL, Flyway, money, time, enumerations as seeded rows, legacy columns | ADM-10, MIG-02; Q-52; 08 §2.1–2.5 amended by 12b §5.4 | Phase 1 |
| 0021 | Legacy import mapping and reconciliation rules | MIG-01, MIG-02; Q-02, Q-42; DQ-01…DQ-37 | Phase 2 · migration |
| 0022 | Infrastructure and deployment baseline: new VPS, Compose, Caddy 2, backups, CI-built images, TLS | NFR-05, NFR-06, NFR-07; Q-48, Q-49, Q-52; F-08…F-12, F-17, F-18 (tests run in the image build, CI, one default branch); OP-S3 (HTTPS only on every host) | Phase 1 |
| 0023 | Cut-over: freeze block list, final import, DNS, rollback | MIG-03; Q-16, Q-19, Q-20, Q-40, Q-41, Q-45, Q-46, Q-47 | Phase 2 · migration (runbook with the integrator) |
| 0024 | Privacy: retention periods, export and erasure by anonymisation, processors | NFR-03; Q-18, Q-43; 10 §5 | Phase 2 · customers |
| 0025 | Feature flags and the M2 scope rules; extension points for the "Later" backlog | ENH-01…ENH-22; 10 §2 "Feature flags"; chapter 09 "Later" and `PROMPT.md` "Later" (extension points only: the in-process domain events of `platform` — customer verified/approved/declined, order created/approved/declined, tracking changed, lead stored — to which the backlog integrations would subscribe; no subscriber code in M1/M2) | Phase 1 (table and service), Phase 5 (per flag group) |
| 0026 | Pricing (M2): rate precision, class selection, no price data in code or fixtures, visibility scopes | ENH-01…ENH-04, ENH-10, ENH-11; Q-14, Q-32, Q-33, Q-34; PR-01…PR-14 | Phase 5 · pricing |
| 0027 | Business configuration as a single source (contacts, hours, VAT, social, site URL) | PUB-02; Q-15; LPS-04, 06 §7.1 | Phase 1 |
| 0028 | Legacy admin tools not ported; post-approval corrections as audited ADMIN actions | ADM note of chapter 09, NFR-03; SEC-15, SEC-16; 12b §3.2 O6, 12c §6 | Phase 2 · orders |

Dropped without an ADR (incidental to the retired SPA, reader 03 §4): OP-19, OP-21, OP-24.
