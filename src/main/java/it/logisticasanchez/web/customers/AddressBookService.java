package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.text.Collator;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The address book of saved recipients (ENH-07, ADR 0033): the customer manages it in the
 * profile and picks an entry in the order form; staff entering an order for the customer
 * (ADM-11) use the same list. An entry has the recipient fields of an order with the same rules
 * (OP-R7: name, street, city, province and a five-digit CAP required, phone and e-mail
 * optional); the same name, street and CAP is one entry, so saving it again from an order
 * updates it. Every change is audited on the customer ({@code entity_type = customer}, the entry
 * id in the values, the recipient in the personal detail), so the erasure of the customer
 * (NFR-03) also removes those details. Flags are checked by the callers (ADR 0025): this service
 * is the data, not the feature switch.
 */
@Service
public class AddressBookService {

    /** More entries than a customer needs: a guard against abuse, not a business rule. */
    public static final int MAX_ENTRIES = 200;

    /** The input names of an entry, the same as the recipient of the order form. */
    public static final List<String> FIELDS =
            List.of("name", "addressLine", "province", "city", "postalCode", "phone", "email");

    static final int NAME_MAX = 200;
    static final int ADDRESS_MAX = 200;
    static final int CITY_MAX = 100;
    static final int PROVINCE_MAX = 60;

    private static final Collator ITALIAN = Collator.getInstance(Locale.ITALIAN);

    private final CustomerAddressRepository addresses;
    private final AuditService audit;
    private final Clock clock;

    public AddressBookService(CustomerAddressRepository addresses, AuditService audit, Clock clock) {
        this.addresses = addresses;
        this.audit = audit;
        this.clock = clock;
    }

    /** Who changes the book: the customer's login identity or a staff user. */
    public record Actor(AuditActorType type, long id) {

        public static Actor customer(long customerUserId) {
            return new Actor(AuditActorType.CUSTOMER, customerUserId);
        }

        public static Actor staff(long staffUserId) {
            return new Actor(AuditActorType.STAFF, staffUserId);
        }
    }

    /** A save: the entry, or the refused fields (input name → message key) with nothing stored. */
    public record Result(AddressView address, Map<String, String> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** The customer's entries by name (Italian collation), then by id. */
    @Transactional(readOnly = true)
    public List<AddressView> list(long customerId) {
        return addresses.findByCustomerId(customerId).stream()
                .map(AddressBookService::view)
                .sorted(Comparator.comparing((AddressView a) -> a.name(), ITALIAN)
                        .thenComparing(AddressView::id))
                .toList();
    }

    /** The customer's entry with this id; empty for another customer's entry. */
    @Transactional(readOnly = true)
    public Optional<AddressView> find(long customerId, long addressId) {
        return addresses.findByIdAndCustomerId(addressId, customerId).map(AddressBookService::view);
    }

    @Transactional
    public Result create(long customerId, Actor actor, Map<String, String> input) {
        Map<String, String> errors = new LinkedHashMap<>();
        AddressView.Fields fields = validate(input, errors);
        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }
        Optional<CustomerAddress> same = entry(customerId, fields);
        if (same.isPresent()) {
            return new Result(null, Map.of("name", "customers.addressBook.error.duplicate"));
        }
        return new Result(insert(customerId, actor, fields), Map.of());
    }

    /** Replaces an entry's values; another customer's entry is a 404. */
    @Transactional
    public Result update(long customerId, long addressId, Actor actor, Map<String, String> input) {
        CustomerAddress address = own(customerId, addressId);
        Map<String, String> errors = new LinkedHashMap<>();
        AddressView.Fields fields = validate(input, errors);
        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }
        Optional<CustomerAddress> same = entry(customerId, fields);
        if (same.isPresent() && !same.get().getId().equals(address.getId())) {
            return new Result(null, Map.of("name", "customers.addressBook.error.duplicate"));
        }
        AddressView.Fields before = address.fields();
        address.replace(fields);
        audited(actor, "address.update", customerId, address.getId(), before, fields);
        return new Result(view(address), Map.of());
    }

    /** Removes an entry; another customer's entry is a 404. */
    @Transactional
    public void delete(long customerId, long addressId, Actor actor) {
        CustomerAddress address = own(customerId, addressId);
        AddressView.Fields before = address.fields();
        addresses.delete(address);
        audited(actor, "address.delete", customerId, addressId, before, null);
    }

    /**
     * «Salva il destinatario nella rubrica» of the order form: the recipient of an order (already
     * valid for the order) becomes an entry, or updates the entry with the same name, street and
     * CAP. Refused only when the book is full.
     */
    @Transactional
    public AddressView remember(
            long customerId,
            Actor actor,
            String name,
            String addressLine,
            String province,
            String city,
            String postalCode,
            String phone,
            String email) {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("name", name);
        input.put("addressLine", addressLine);
        input.put("province", province);
        input.put("city", city);
        input.put("postalCode", postalCode);
        input.put("phone", phone);
        input.put("email", email);
        Map<String, String> errors = new LinkedHashMap<>();
        AddressView.Fields fields = validate(input, errors);
        if (!errors.isEmpty()) {
            throw new RuleViolationException("Il destinatario non può essere salvato nella rubrica.");
        }
        Optional<CustomerAddress> same = entry(customerId, fields);
        if (same.isEmpty()) {
            return insert(customerId, actor, fields);
        }
        CustomerAddress address = same.get();
        AddressView.Fields before = address.fields();
        if (!before.equals(fields)) {
            address.replace(fields);
            audited(actor, "address.update", customerId, address.getId(), before, fields);
        }
        return view(address);
    }

    /** Erasure (NFR-03): deletes every entry of the customer; returns how many. */
    @Transactional
    public int erase(long customerId) {
        return addresses.deleteByCustomer(customerId);
    }

    private AddressView insert(long customerId, Actor actor, AddressView.Fields fields) {
        if (addresses.countByCustomerId(customerId) >= MAX_ENTRIES) {
            throw new RuleViolationException(
                    "La rubrica può contenere al massimo " + MAX_ENTRIES + " destinatari: eliminane qualcuno.");
        }
        CustomerAddress address = addresses.save(new CustomerAddress(customerId, fields, clock.instant()));
        audited(actor, "address.create", customerId, address.getId(), null, fields);
        return view(address);
    }

    /** One audit entry on the customer: the entry id in the values, the recipient in the personal detail. */
    private void audited(
            Actor actor,
            String action,
            long customerId,
            long addressId,
            AddressView.Fields before,
            AddressView.Fields after) {
        audit.record(AuditEntry.of(actor.type(), actor.id(), action)
                .entity("customer", customerId, null)
                .change(
                        before == null ? null : Map.of("addressId", addressId),
                        after == null ? null : Map.of("addressId", addressId))
                .personal(before == null ? null : personal(before), after == null ? null : personal(after)));
    }

    private CustomerAddress own(long customerId, long addressId) {
        return addresses
                .findByIdAndCustomerId(addressId, customerId)
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private Optional<CustomerAddress> entry(long customerId, AddressView.Fields fields) {
        return addresses.findEntry(
                customerId,
                fields.name().toLowerCase(Locale.ROOT),
                fields.addressLine().toLowerCase(Locale.ROOT),
                fields.postalCode());
    }

    /** The recipient rules of the order form (OP-R7), with the same lengths as the order's columns. */
    static AddressView.Fields validate(Map<String, String> input, Map<String, String> errors) {
        String name = required(input, "name", NAME_MAX, errors);
        String addressLine = required(input, "addressLine", ADDRESS_MAX, errors);
        String province = required(input, "province", PROVINCE_MAX, errors);
        String city = required(input, "city", CITY_MAX, errors);
        String postalCode = ItalianFormats.trim(input.get("postalCode"));
        if (postalCode.isEmpty()) {
            errors.put("postalCode", "orders.error.required");
        } else if (!ItalianFormats.isCap(postalCode)) {
            errors.put("postalCode", "orders.error.cap");
        }
        String phone = null;
        String rawPhone = ItalianFormats.trim(input.get("phone"));
        if (!rawPhone.isEmpty()) {
            Optional<String> normalised = ItalianFormats.normalisePhone(rawPhone);
            if (normalised.isEmpty()) {
                errors.put("phone", "orders.error.phone");
            } else {
                phone = normalised.get();
            }
        }
        String email = null;
        String rawEmail = ItalianFormats.normaliseEmail(input.get("email"));
        if (!rawEmail.isEmpty()) {
            if (!ItalianFormats.isEmail(rawEmail)) {
                errors.put("email", "orders.error.email");
            } else {
                email = rawEmail;
            }
        }
        return new AddressView.Fields(name, addressLine, province, city, postalCode, phone, email);
    }

    private static String required(Map<String, String> input, String field, int max, Map<String, String> errors) {
        String value = ItalianFormats.trim(input.get(field));
        if (value.isEmpty()) {
            errors.put(field, "orders.error.required");
        } else if (value.length() > max) {
            errors.put(field, "orders.error.tooLong");
        }
        return value;
    }

    private static Map<String, Object> personal(AddressView.Fields f) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(
                "recipient",
                f.name() + ", " + f.addressLine() + ", " + f.postalCode() + " " + f.city() + " (" + f.province() + ")");
        values.put("phone", f.phone() == null ? "-" : f.phone());
        values.put("email", f.email() == null ? "-" : f.email());
        return values;
    }

    private static AddressView view(CustomerAddress address) {
        return new AddressView(address.getId(), address.fields());
    }
}
