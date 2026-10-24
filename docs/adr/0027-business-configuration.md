# 0027 · Business configuration as a single source

* **Status:** Accepted
* **Date:** 2026-10-23
* **Requirement IDs:** PUB-02 · **Questions:** Q-15, Q-43, Q-37 · **Findings:** LPS-04; chapter 06 §7.1

## Context

On the static site the company data are repeated by hand: the phone number appears 312
times in 27 files, the e-mail 195 times, the address 143 times, the VAT number 53 times
(chapter 06 §7.1); the legacy backend hard-coded the office address. Chapter 01 "The
company" holds the authoritative values, which are public company data. PUB-02 asks for one
business configuration feeding fragments, JSON-LD, `config.js`, e-mail templates, error
pages and `security.txt`; Q-15 keeps one official number, Q-43 makes the office mailbox a
setting.

## Decision

* `BusinessProperties` (`ls.business.*`, `platform.config`) is the only place the company
  data live. Defaults are the chapter 01 values; every field can be overridden from the
  environment (`LS_BUSINESS_<FIELD>`):

  | Key | Default | Used by |
  |---|---|---|
  | `legal-name` | `Logistica Sanchez S.r.l.` | footer, privacy, e-mail footer |
  | `brand-name` | `Logistica Sanchez` (Q-37) | copy, e-mail subjects, error pages |
  | `slogan` | `Il pallet espresso` | header, footer, JSON-LD |
  | `vat-number` | `13113080967` (`vatId()` → `IT13113080967`) | footer, JSON-LD `vatID`, privacy |
  | `address.*` | Via dei Meiani, 90 · 20831 · Seregno · MB · IT (`oneLine()`) | topbar, footer, JSON-LD, contacts, map facades |
  | `phone` | `+39 329 239 6962` (`phoneDigits()` → `+393292396962`) — **Q-15: one number** | `tel:` links, `config.js`, e-mails, error pages |
  | `whatsapp` | `393292396962` (`whatsappUrl()`) — Q-15 | `wa.me` links, `config.js` |
  | `office-email` | `logisticasanchezmb@gmail.com` — **Q-43** | EML-06/07/08 recipient, Reply-To, contacts, privacy, error pages, `security.txt` |
  | `hours.*` | weekdays `8:00–18:00`, Saturday `8:00–12:00`, short label `Lun–Ven 8–18 · Sab 8–12` | header, contacts table, JSON-LD opening hours |
  | `social.instagram` | `https://www.instagram.com/sanchez_logistica_mb` | footer, JSON-LD `sameAs` |
  | `site-url` | `https://logisticasanchez.com` | canonicals, OG, sitemap, robots, `security.txt`, every e-mail link, host-routing targets (ADR 0019 "one base URL") |

* The record is exposed to every template as the model attribute `business`
  (`PlatformModelAttributes`) and to the error pages of ADR 0002; the `site` module renders
  `config.js`, JSON-LD and the fragments from it (stage 5 and Phase 2); the `notifications`
  module takes Reply-To and the office recipient from it; the `reports` module's default
  recipient list is the office address (Q-08).
* The values are validated at start-up (`@Validated`: non-blank, VAT number eleven digits,
  WhatsApp digits only, site URL an origin without trailing slash).
* **Not business configuration:** the WhatsApp prefilled messages (chapter 06 §7.2) are
  page copy and stay message keys; the credit line «Sito realizzato da Alpacode» is page
  copy of the `site` module.

## Consequences

* A change of number, mailbox or hours is one environment variable (or one default) and one
  deploy, not a search-and-replace over the templates; the PUB-01 parity harness lists the
  generated fragments as allowed differences (ADR 0012).
* The office mailbox is a consumer Gmail address until the owner provides a business one
  (Q-43, chapter 10 §5): the privacy page names whatever the setting holds.
* Test and demo data never override these values with real personal data; the defaults are
  the company's public contacts.

## References

Chapter 01 "The company"; chapter 06 §7.1; chapter 09 PUB-02; chapter 11 Q-15, Q-37, Q-43;
`docs/plan.md` §4.3 "Business configuration", §8.1; ADR 0012, ADR 0015, ADR 0019.
