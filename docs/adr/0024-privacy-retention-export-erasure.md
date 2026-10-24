# 0024 · Privacy: retention periods, export and erasure by anonymisation, processors

* **Status:** Accepted
* **Date:** 2026-10-24
* **Requirement IDs:** NFR-03 (chapter 09; chapter 10 §5) · **Questions:** Q-18 (retention periods), Q-43 (e-mail provider as processor)

## Context

The legacy kept everything forever, logged request and response bodies (12c §3.5), offered
hard-delete admin tools instead of erasure and had no export. Chapter 10 §5 asks for export and
erasure on request, erasure as anonymisation (orders kept for the legal period), no personal
data in logs or in the audit log beyond ids, and legal pages that mention the customer area
and the processors.

## Decision

* **Export** (`PrivacyService.export`, ADMIN, `/gestione/impostazioni/privacy/`): a JSON
  document with the customer row, the login identity, every order with its snapshots and
  lines, and the leads sent from the site with that address. Audited as `privacy.export` with
  the customer id only.
* **Erasure = anonymisation** (`PrivacyService.anonymise`, ADMIN, behind a confirmation): each
  module does its part through its service, in one transaction —
  * `customers`: e-mail → `cliente-<id>@anonimizzato.invalid`, name «Cliente anonimizzato»,
    address, city, province, phone «-», CAP `00000`, VAT and tax code null, status `DECLINED`,
    `anonymised_at` set (V012); the row and its id stay;
  * `auth`: every login identity of the customer gets the placeholder and is disabled, its
    one-time links are deleted, its sessions end;
  * `notifications`: every outbox row addressed to, replying to, or mentioning the address loses
    bodies and token; the address is replaced where it is the recipient or the Reply-To; a row
    still queued becomes `FAILED`;
  * `orders`: the staff notes of the customer's orders are deleted; **the orders stay with
    their sender and recipient snapshots** (legal retention, Q-18);
  * `site`: the leads of the address are anonymised (name «-», e-mail
    `lead-<id>@anonimizzato.invalid`, phone/message/company/goods null or «-», payload `{}`);
  * `reports`: the address is replaced in `report_run.recipients`;
  * `platform`: `audit_log_detail` rows about the customer, its orders, made by its login or
    mentioning the address are deleted in a maintenance transaction (ADR 0020); the
    `audit_log` rows (ids only) stay.
  Audited as `privacy.anonymise` with counts. The test of NFR-03 scans every text column of
  every table: after the erasure the address survives only in `shipment_order.sender_email`.
* **Retention jobs** (`RetentionRunner`, `ls.retention.*`): login tokens 24 h after expiry
  (`auth`), **leads anonymised after `retain_until`** (24 months, `site`), **outbox bodies
  purged 30 days after delivery** (`notifications`). Audit rows (24 months), IP hashes
  (30 days) and report runs (24 months) follow in the retention stage.
* **Legal pages.** `/privacy/` gains the customer-area data, the hosting provider as processor,
  the retention periods of the customer area and the export/erasure sentence; `/cookie/`
  gains the single technical session cookie `__Host-LS_SESSION` of the private areas. Both
  additions are applied by `tools/port-ls-home-pages.py` (`ADDITIONS`), plain text for the
  owner's review.

## Consequences

* An erased customer cannot sign in, order or be approved again; a new registration with the
  same address creates a new customer.
* Order snapshots keep the personal data of the sender for the legal period: the privacy page
  says so.
