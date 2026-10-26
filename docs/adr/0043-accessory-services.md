# 0043 · Accessory services managed from the back office (ENH-14)

* **Status:** Accepted
* **Date:** 2026-10-26
* **Requirement IDs:** ENH-14 (flag `admin.enumerations`); touches ADM-10, API-02, PUB-01, PUB-06 · **Owner:** request of 2026-10-26 («procedi con quelli che puoi fare ora»)

## Context

Chapter 09: «Manage accessory services from the back office; the public `/servizi/` list and the
quote-wizard extras read the enumeration (field values unchanged, PUB-06).» Three lists name
accessory services today, and they differ:

* the order (V004/V009, ADM-10): «Nessun servizio accessorio» (item 0), «Prenotazione telefonica»,
  «Sponda idraulica», «Segnalazione merce lunga», «Facchinaggio», «GDO» — the legacy enumeration
  served by EP-20 (API-02);
* «Servizi accessori» of `/servizi/` (ported, PUB-01): six cards with icon and text, «Sponda
  idraulica» the only one also on the order;
* the extras of the quote wizard (PUB-06): four checkboxes whose label is the value sent with the
  request, checked against a fixed list by the server.

Reading one list for all three as it stands would change what visitors read and the values the
wizard sends.

## Decision

* **One list, each row saying where it shows** (V023): `in_orders` (the order forms and EP-20; the
  six M1 rows), `on_site` (a card of «Servizi accessori», with its `description` and an `icon` of
  `/img/icons.svg`), `in_quote` (an extra of the wizard: the label is the field value, with an
  optional `quote_hint`, the small line under it). V023 seeds the cards and extras of the ported
  pages with their texts — «Sponda idraulica» gets its card and its extra, the six other services
  of the two pages become rows not on the order — so that turning the flag on changes nothing a
  visitor reads.
  `sort_order` is one order for the three places; EP-20 stays ordered by id.
* **«Servizi accessori» under «Impostazioni»** (ADMIN only, `/gestione/impostazioni/servizi-accessori/`):
  the list with where each row shows and its state; «Su» / «Giù», «Modifica», «Disattiva» /
  «Riattiva», «Elimina»; the form of a new service: its name (≤ 120), the places (at least one),
  the text (≤ 300) and the icon (one of 20 symbols of the site) when it shows on the site, the
  hint (≤ 120). A new service takes the next id and a code made from its first name (capitals,
  no accents, `_`, a suffix when taken), which never changes. Names are unique, case aside. Audit:
  `accessory.service.create|update|move|activate|deactivate|delete`.
* **What stays fixed:** item 0 is listed and never changed (always the first choice of the order).
  The legacy rows 1–5 keep their id, code and place on the order (EP-20 always serves them; their
  checkbox is disabled) and are never deleted: they can be renamed, moved, switched off. Another
  row is deleted only when no order or saved quote uses it (the foreign keys say so; the office is
  told to switch it off instead).
* **Switched off** = hidden everywhere: the order forms, `/servizi/`, the wizard and the request
  checks. EP-20 still lists a switched-off order service (as an enumeration lists all its values),
  and an order that has one keeps it while it is edited (`TrackingCatalog.activeServices(chosen)`).
* **The order side never takes a row of the public lists only:** `OrderValidator` and the saved
  quotes accept `in_orders` rows only (`TrackingCatalog.orderService`), with the flag on or off.
* **The public pages:** the port script adds, after the ported block of `/servizi/` and of the
  wizard, a block drawn from the list (`siteAccessories`, `quoteExtras`, set only on those two
  pages and only with the flag on); the ported block carries the opposite `th:if`. The output
  with the flag off is the ported page, byte for byte where it matters (PUB-01, `SitePagesIT`);
  `tools/port-ls-home-pages.py --check` stays meaningful. With the flag on, `FormValidator`
  accepts the extras of the list (their labels), not the fixed ones.

### Flag off

The pages render their ported lists whatever the table holds, the request accepts the ported
extras only, the order forms, the validator and EP-20 know the six legacy services, «Impostazioni»
has no link and the routes answer 404 (`AccessoryServicesOffIT`). ADM-10 now reads «the six
legacy services of the order» (`EnumerationSeedIT`); the table holds twelve rows.

## Consequences

* The office changes the services and the two public lists without a release; the texts of the
  public cards become the office's to keep.
* A renamed legacy service changes its label on EP-20 (id and code stay).
* An extra renamed while a visitor fills the wizard is dropped from that request, as an unknown
  value always was.
* The new Italian texts of the back-office page wait for the owner's review.

## References

Chapter 09 ENH-14, ADM-10, API-02, PUB-01, PUB-06; ADR 0012 (port allow-list), 0020 (enumeration
keys), 0025 (flags). Code: `tracking.AccessoryServiceAdmin`, `TrackingCatalog`,
`CompatApiController`, `site.SiteAccessoryModelAttributes`, `site.forms.FormValidator`,
`backoffice.BackofficeAccessoryServicesController`, `templates/backoffice/accessory-services.html`,
`templates/site/servizi.html`, `templates/site/preventivo.html`, `tools/port-ls-home-pages.py`;
V023. Tests: `AccessoryServicesIT`, `AccessoryServicesOffIT`, `EnumerationSeedIT`, `CompatApiIT`,
`e2e/tests/m2.spec.ts` [ENH-14].
