# 0014 · `POST /api/form`: form.mjs behaviour, strict lengths, Origin check, rate limit, lead storage

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** PUB-06, PUB-07, PUB-11, EML-08 · **Questions:** Q-18, Q-43, PD-04 · **Findings:** chapter 06 §5.5 "Fix", D-38

## Context

The quote wizard and the contact form post url-encoded fields to `/api/form`, by `fetch` with
`Accept: application/json` or natively (chapter 06 §5.5–5.6). The Netlify function `form.mjs`
validated, composed one e-mail to the office through SES and answered `{ok}` JSON or a 303 to
`/grazie/`; it **cut every value to its maximum length before checking it**, so
`consegna_cap=401234` was accepted as `40123` and a 150-character name as 100 (§5.5). Chapter 09
asks for the same behaviour with that quirk fixed, an e-mail through the outbox with
Reply-To = customer, a honeypot, a rate limit, an `Origin`/`Referer` check (the endpoint is
CSRF-exempt, chapter 10 §4) and a `lead` row in the same transaction (PUB-11).

## Decision

* **Pipeline as `form.mjs`** (`FormController`): JSON mode when `Accept` carries
  `application/json`, native mode otherwise; `Content-Length` > 20,000 → 413 `too_large`;
  non-empty `bot-field` → fake success (nothing stored, nothing queued); `form-name` not
  `preventivo`/`contatti` → 400 `unknown_form`; the first failing field → 422
  `invalid:<field>` in the legacy check order; lead or outbox write failing → 502
  `send_failed`; success → `200 {"ok":true}` or `303 Location: /grazie/`. Native-mode
  failures render `site/form-error.html` («Invio non riuscito», the legacy sentences, «Torna
  al modulo» → `/<form>/`) with the same status.
* **Deliberate change — no truncation** (`FormValidator`): a value longer than its maximum
  (`nome` 100, `email` 254, `telefono` 30, `ritiro_localita`/`consegna_localita` 80,
  `azienda` 120, `descrizione_merce` 200, `note` 3000, `messaggio` 5000, `oggetto` 100,
  `data_ritiro` 10, `numero` 6, `peso` 8, `altezza` 6, default 200) is refused with 422
  `invalid:<field>`, and a CAP must be exactly five digits. Everything else is the legacy
  rule: allowed values and labels of §5.5, `telefono` regex `^[0-9+\s().\/-]{6,20}$` (required
  for the quote; 21–30 characters were refused by the legacy too, D-38), `privacy=accettata`,
  `messaggio` ≥ 10, unknown `servizi_extra[]` dropped silently, past pick-up dates accepted,
  an unparseable `data_ritiro` → «Da concordare».
* **PD-04**: the decimal comma in `numero` stays accepted (`2,5` → 2.5, printed `2.5 ×`);
  `ls.site.form.quantity-integer-only=true` refuses it.
* **Added by the monolith**: the request must carry an `Origin` equal to one of the site's
  origins (`ls.business.site-url`, `https://` + the canonical, `www` and `app` hosts) or to the
  request's own origin (localhost, staging); without `Origin`, a `Referer` on one of them;
  with neither → 403 `forbidden`. Rate limit `form.submit`, 10 submissions per 10 minutes per
  client IP → 429 `too_many_requests` with `Retry-After` (both answers in the form.mjs shapes,
  not the platform schema, so `fetch` callers keep reading `{ok, error}`).
* **Storage in one transaction** (`LeadService`): the `lead` row (status `NEW`, the typed
  columns of V006, `extras`, `payload` = every accepted field as received, `ip_hash` = HMAC
  of the client IP, `user_agent` ≤ 255, `retain_until` = today + `ls.retention.leads-months`,
  Q-18) and the EML-08 outbox row (`FormMail`: subject, text and HTML of `form.mjs`'s `compose`
  verbatim — sections, omitted empty rows, «Rispondi via email» / «Chiama» / «Scrivi su
  WhatsApp» with the Italian-mobile rule, footer with the Europe/Rome time; recipient =
  `ls.business.office-email`, Q-43; Reply-To = the customer). `DomainEvent.LeadStored` is
  published. No e-mail is ever sent from the controller.
* **Not reproduced**: the body-length re-check after reading (the container bounds the body;
  `Content-Length` is checked), `multipart/form-data` edge cases beyond what the servlet
  parses.

## Consequences

* A visitor who types a six-digit CAP sees the field error instead of a silently wrong
  address in the office e-mail; the client-side `maxlength=5` already prevents it with
  JavaScript on.
* Public HTML stays cookie-free and cacheable: no CSRF token is needed on the public pages.
* The office e-mail is identical to the legacy one (asserted by `FormMailTest` on the chapter
  06 sample); the `notifications` module may restyle it under ADR 0015 later.
* Leads are stored with personal data for 24 months by default (Q-18); the retention job of
  the `site` module (anonymisation after `retain_until`) is part of the retention stage.

## References

Chapter 06 §5.5, §5.6, §8.5; chapter 09 PUB-06, PUB-07, PUB-11, EML-08; chapter 10 §4;
chapter 11 Q-18, Q-43; plan D-38, PD-04; ADR 0002 (rate limiter), 0015 (outbox), 0027. Code:
`it.logisticasanchez.web.site.forms.*`, `FormValidatorTest`, `FormMailTest`, `FormEndpointIT`.
