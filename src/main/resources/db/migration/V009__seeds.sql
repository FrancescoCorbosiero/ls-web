-- V009: seeds (ADM-10, Q-25; ADR 0025). Only enumerations and feature flags: no staff
-- account (ADM-01), no customer, no order is ever inserted by a migration.

-- The six tracking states, exactly as the legacy backend declares them (12b §2.2, chapter
-- 02 §9.1): ids, names and labels in declaration order. public_title and step_label come
-- from the new site's tracking page (chapter 06 §5.7).
INSERT INTO tracking_state (id, name, label, public_title, step_label, sort_order) VALUES
    (0, 'NON_GESTITO',   'Non gestito',           'In attesa di presa in carico',           NULL,                      0),
    (1, 'PRESA',         'Presa in carico',       'Presa in carico',                        'Presa in carico',         1),
    (2, 'TRANSITO',      'Transito',              'In transito',                            'In transito',             2),
    (3, 'TERMINAL_DEST', 'Terminal destinatario', 'Arrivata al terminal di destinazione',   'Terminal di destinazione', 3),
    (4, 'ASSEGNATO',     'Assegnato',             'Affidata al mezzo per la consegna',      'In consegna',             4),
    (5, 'CONSEGNATO',    'Consegnato',            'Consegnata',                             'Consegnata',              5);

-- The six accessory services, exactly as the legacy backend declares them (12b §2.2,
-- chapter 02 §9.2): item 0 is "none" and is the only is_none row.
INSERT INTO accessory_service (id, name, label, is_none, active, sort_order) VALUES
    (0, 'NESSUN_SERVIZIO',  'Nessun servizio accessorio', true,  true, 0),
    (1, 'PRENOTAZIONE_TEL', 'Prenotazione telefonica',    false, true, 1),
    (2, 'SPONDA_IDR',       'Sponda idraulica',           false, true, 2),
    (3, 'MERCE_LUNGA',      'Segnalazione merce lunga',   false, true, 3),
    (4, 'FACCHINAGGIO',     'Facchinaggio',               false, true, 4),
    (5, 'GDO',              'GDO',                        false, true, 5);

-- Rows added later (ENH-14) continue after the legacy ids.
ALTER TABLE accessory_service ALTER COLUMN id RESTART WITH 6;

-- Every M2 flag of chapter 09 §M2, off (ADR 0025).
INSERT INTO feature_flag (key, enabled, requirement_id) VALUES
    ('pricing.lists',            false, 'ENH-01'),
    ('pricing.suggestedAmount',  false, 'ENH-02'),
    ('pricing.customerEstimate', false, 'ENH-03'),
    ('pricing.publicEstimate',   false, 'ENH-04'),
    ('tracking.history',         false, 'ENH-05'),
    ('tracking.notifyCustomer',  false, 'ENH-05'),
    ('leads.inbox',              false, 'ENH-06'),
    ('customers.addressBook',    false, 'ENH-07'),
    ('orders.repeat',            false, 'ENH-07'),
    ('admin.dashboard',          false, 'ENH-08'),
    ('admin.exports',            false, 'ENH-08'),
    ('security.adminTotp',       false, 'ENH-09'),
    ('delivery.unifiedTimes',    false, 'ENH-10'),
    ('pricing.marginView',       false, 'ENH-11'),
    ('documents',                false, 'ENH-12'),
    ('admin.audit',              false, 'ENH-13'),
    ('admin.enumerations',       false, 'ENH-14'),
    ('orders.bulkTracking',      false, 'ENH-15'),
    ('customers.profileEdit',    false, 'ENH-16'),
    ('site.noticeBanner',        false, 'ENH-17'),
    ('site.openNow',             false, 'ENH-18'),
    ('site.pickupCutoff',        false, 'ENH-19'),
    ('quotes.saved',             false, 'ENH-20'),
    ('documents.monthlyReport',  false, 'ENH-21'),
    ('site.liveCounters',        false, 'ENH-22');
