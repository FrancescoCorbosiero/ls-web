package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.customers.AddressBookService;
import it.logisticasanchez.web.customers.AddressView;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The address book in the order forms (ENH-07, flag {@code customers.addressBook}; ADR 0033),
 * shared by the customer's form and the staff form (ADM-11): the list to pick from, «Usa questo
 * destinatario» and «Salva il destinatario nella rubrica». The single place where the order forms
 * read the flag: while it is off the list is null (the template shows nothing), the action is read
 * as M1 reads an unknown one (save) and nothing is remembered.
 */
@Component
public class OrderAddressBook {

    private final AddressBookService book;
    private final FeatureFlags flags;
    private final OrderForm forms;

    public OrderAddressBook(AddressBookService book, FeatureFlags flags, OrderForm forms) {
        this.book = book;
        this.flags = flags;
        this.forms = forms;
    }

    public boolean enabled() {
        return flags.isEnabled(FlagKey.CUSTOMERS_ADDRESS_BOOK);
    }

    /** The customer's saved recipients for the form, or null while the flag is off or no customer is chosen. */
    public List<AddressView> entries(Long customerId) {
        return customerId != null && enabled() ? book.list(customerId) : null;
    }

    /** The button pressed, as M1 reads it while the flag is off («Usa questo destinatario» and «Mostra la rubrica» did not exist). */
    public OrderForm.Action action(Map<String, String> form) {
        OrderForm.Action action = forms.action(form);
        boolean bookAction = action == OrderForm.Action.USE_ADDRESS || action == OrderForm.Action.SHOW_ADDRESS_BOOK;
        return bookAction && !enabled() ? OrderForm.Action.SAVE : action;
    }

    /** «Usa questo destinatario»: the form with the chosen entry, or with an error on the choice; never another customer's entry. */
    public OrderForm.Model use(Long customerId, OrderForm.Model typed) {
        String raw = typed.value("addressId");
        Optional<AddressView> entry = customerId == null || !raw.matches("^[0-9]{1,18}$")
                ? Optional.empty()
                : book.find(customerId, Long.parseLong(raw));
        if (entry.isEmpty()) {
            return forms.withError(typed, "addressId", "orders.addressBook.unknown");
        }
        AddressView a = entry.get();
        return forms.withRecipient(
                typed,
                new OrderDraft.Recipient(
                        a.name(), a.addressLine(), a.province(), a.city(), a.postalCode(), a.phone(), a.email()));
    }

    /** True when the form asks to save its recipient («Salva il destinatario nella rubrica») and the flag is on. */
    public boolean asked(OrderForm.Model typed) {
        return enabled() && "1".equals(ItalianFormats.trim(typed.value("saveRecipient")));
    }

    /**
     * Saves the recipient of an order that was just sent or saved: a warning to show (the book is
     * full), or null. Never undoes the order: the book is a convenience.
     */
    public String remember(long customerId, AddressBookService.Actor actor, OrderDraft.Recipient r) {
        if (!enabled()) {
            return null;
        }
        try {
            book.remember(
                    customerId,
                    actor,
                    r.name(),
                    r.addressLine(),
                    r.province(),
                    r.city(),
                    r.postalCode(),
                    r.phone(),
                    r.email());
            return null;
        } catch (RuleViolationException e) {
            return "Il destinatario non è stato salvato nella rubrica: ha già il numero massimo di voci ("
                    + AddressBookService.MAX_ENTRIES + ").";
        }
    }
}
