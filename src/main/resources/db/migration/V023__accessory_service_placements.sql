-- ENH-14 (flag admin.enumerations, ADR 0043): the accessory services become one list managed
-- from the back office, for the three places that name them: the order (in_orders: the six M1
-- rows; EP-20 serves only these, API-02), the «Servizi accessori» section of /servizi/ (on_site,
-- with its text and icon) and the extras of the quote wizard (in_quote: the label is the value of
-- servizi_extra[], PUB-06, with the hint under it). The rows of the two public lists are seeded
-- with the texts of the ported pages, so that turning the flag on changes nothing a visitor reads;
-- with the flag off the pages keep their ported lists and these rows are never shown.
ALTER TABLE accessory_service
    ADD COLUMN in_orders   boolean      NOT NULL DEFAULT true,
    ADD COLUMN on_site     boolean      NOT NULL DEFAULT false,
    ADD COLUMN in_quote    boolean      NOT NULL DEFAULT false,
    ADD COLUMN description varchar(300),
    ADD COLUMN icon        varchar(40),
    ADD COLUMN quote_hint  varchar(120),
    ADD CONSTRAINT ck_accessory_service_placement CHECK (in_orders OR on_site OR in_quote),
    ADD CONSTRAINT ck_accessory_service_site CHECK (NOT on_site OR (description IS NOT NULL AND icon IS NOT NULL)),
    ADD CONSTRAINT ck_accessory_service_none CHECK (NOT is_none OR (in_orders AND active AND NOT on_site AND NOT in_quote));

COMMENT ON COLUMN accessory_service.in_orders IS 'Offered on an order (customer area and back office) and served by EP-20.';
COMMENT ON COLUMN accessory_service.on_site IS 'Shown in «Servizi accessori» of /servizi/ while admin.enumerations is on (ENH-14).';
COMMENT ON COLUMN accessory_service.in_quote IS 'An extra of the quote wizard while admin.enumerations is on (ENH-14); the label is the field value.';
COMMENT ON COLUMN accessory_service.description IS 'The paragraph under the label on /servizi/; required when on_site.';
COMMENT ON COLUMN accessory_service.icon IS 'A symbol of /img/icons.svg (i-...) shown on /servizi/; required when on_site.';
COMMENT ON COLUMN accessory_service.quote_hint IS 'The short line under the extra in the quote wizard; optional.';

UPDATE accessory_service
   SET on_site = true,
       in_quote = true,
       description = 'Il pallet viene calato a terra con la pedana del mezzo: indispensabile se a destinazione non ci sono muletto o banchina di carico.',
       icon = 'i-arrow-down-to-line',
       quote_hint = 'A destinazione non c''è un muletto'
 WHERE name = 'SPONDA_IDR';

-- The other services of the two public lists, in the order of the ported pages; they continue
-- after the legacy ids (V009) and are not offered on orders.
INSERT INTO accessory_service (name, label, sort_order, in_orders, on_site, in_quote, description, icon, quote_hint) VALUES
    ('CONSEGNA_APPUNTAMENTO', 'Consegna su appuntamento', 6, false, true, true,
     'Concordiamo con il destinatario giorno e fascia oraria: utile per cantieri, negozi, magazzini con orari ristretti e privati.',
     'i-calendar-clock', 'Giorno e fascia oraria concordati'),
    ('PREAVVISO_DESTINATARIO', 'Preavviso al destinatario', 7, false, true, true,
     'Avvisiamo il destinatario prima dell''arrivo, così trova qualcuno pronto a ricevere la merce.',
     'i-phone-call', 'Telefonata prima della consegna'),
    ('RITIRI_PROGRAMMATI', 'Ritiri programmati', 8, false, true, false,
     'Per chi spedisce spesso: passiamo a ritirare nei giorni fissi che preferisci, senza doverlo prenotare ogni volta.',
     'i-repeat', NULL),
    ('PROVA_CONSEGNA_DIGITALE', 'Prova di consegna digitale', 9, false, true, false,
     'A consegna avvenuta ricevi la POD firmata dal destinatario, da archiviare o girare al tuo cliente.',
     'i-file-check', NULL),
    ('COPERTURA_ASSICURATIVA', 'Copertura assicurativa integrativa', 10, false, true, false,
     'Per merci di valore puoi richiedere una copertura che va oltre i limiti di responsabilità previsti per legge.',
     'i-shield-check', NULL),
    ('MERCE_FRAGILE', 'Merce fragile o non sovrapponibile', 11, false, false, true,
     NULL, NULL, 'Viaggia senza altri carichi sopra');
