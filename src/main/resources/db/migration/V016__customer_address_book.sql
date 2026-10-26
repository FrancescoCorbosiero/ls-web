-- V016: the address book of saved recipients (chapter 09 ENH-07, flag customers.addressBook;
-- ADR 0033). A customer keeps the recipients it ships to and picks one in the order form; staff
-- entering an order by phone (ADM-11) use the same list. The table belongs to the customers
-- module (customer data); the orders module reads and writes it through AddressBookService.
-- An entry has the recipient fields of an order (OP-R7) with the same lengths and rules. The
-- same name, street and CAP (ignoring case and the spaces around them) is one entry: saving it
-- again from an order updates it. Anonymisation (NFR-03) deletes the customer's entries.

CREATE TABLE customer_address (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     bigint        NOT NULL REFERENCES customer (id),
    name            varchar(200)  NOT NULL,
    address_line    varchar(200)  NOT NULL,
    city            varchar(100)  NOT NULL,
    province        varchar(60)   NOT NULL,
    postal_code     varchar(10)   NOT NULL,
    phone           varchar(30),
    email           varchar(254),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_customer_address_required
        CHECK (btrim(name) <> '' AND btrim(address_line) <> '' AND btrim(city) <> '' AND btrim(province) <> ''),
    CONSTRAINT ck_customer_address_postal_code CHECK (postal_code ~ '^[0-9]{5}$')
);

CREATE UNIQUE INDEX ux_customer_address_customer_name_address_line_postal_code
    ON customer_address (customer_id, lower(btrim(name)), lower(btrim(address_line)), postal_code);

CREATE TRIGGER trg_customer_address_updated_at
    BEFORE UPDATE ON customer_address
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE customer_address IS
    'Saved recipients of a customer (ENH-07, flag customers.addressBook): picked in the order form by the customer and by staff entering an order for it (ADM-11). One entry per name, street and CAP; at most 200 per customer (AddressBookService.MAX_ENTRIES, a guard against abuse). Deleted by the anonymisation (NFR-03).';
COMMENT ON COLUMN customer_address.name IS '«Ragione Sociale/Nominativo» of the recipient, as in shipment_order.recipient_name.';
COMMENT ON COLUMN customer_address.phone IS 'Optional, normalised as the order''s recipient phone.';
COMMENT ON COLUMN customer_address.email IS 'Optional recipient e-mail, lower-case.';
