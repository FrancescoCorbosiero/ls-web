package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.OrdersProperties;
import it.logisticasanchez.web.platform.events.DomainEvent;
import it.logisticasanchez.web.platform.events.DomainEvents;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import it.logisticasanchez.web.tracking.TrackingState;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The orders module's public service (plan §4.1): the wizard submission (CUS-04: ACTIVE sender
 * only, temporary code {@code TEMP-xxxxxxxx}, idempotent on the draft's key, totals, snapshots,
 * EML-04 + EML-06), the customer's list and detail (CUS-05: own orders only), and the back-office
 * rules of chapter 08 §2.4 — approve with a unique code and an amount > 0 (ADM-04, Q-06, 12c
 * §4.2; PRESA event, EML-05), decline (EML-05), tracking state changes as events with EML-09
 * only when the state becomes CONSEGNATO (ADM-06) and, with ENH-05 on, EML-10 for the states in
 * between ({@link TrackingUpdateNotice}), the internal note (ADM-07). Every change writes the
 * audit log in the same transaction.
 */
@Service
public class OrderService {

    static final int CODE_MAX = 64;
    private static final String TEMP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShipmentOrderRepository orders;
    private final TrackingEventRepository trackingEvents;
    private final OrderInternalNoteRepository notes;
    private final CustomerService customers;
    private final TrackingCatalog catalog;
    private final OrderMails mails;
    private final NotificationService notifications;
    private final AuditService audit;
    private final DomainEvents events;
    private final OrdersProperties rules;
    private final Clock clock;
    private final TrackingUpdateNotice trackingUpdates;

    public OrderService(
            ShipmentOrderRepository orders,
            TrackingEventRepository trackingEvents,
            OrderInternalNoteRepository notes,
            CustomerService customers,
            TrackingCatalog catalog,
            OrderMails mails,
            NotificationService notifications,
            AuditService audit,
            DomainEvents events,
            OrdersProperties rules,
            Clock clock,
            TrackingUpdateNotice trackingUpdates) {
        this.orders = orders;
        this.trackingEvents = trackingEvents;
        this.notes = notes;
        this.customers = customers;
        this.catalog = catalog;
        this.mails = mails;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.rules = rules;
        this.clock = clock;
        this.trackingUpdates = trackingUpdates;
    }

    /**
     * CUS-04: creates the PENDING order of the signed-in customer from a complete draft. A draft
     * whose idempotency key already produced an order returns that order and creates nothing
     * (OP-02 fix). Refused unless the customer is ACTIVE (OP-R2, CUS-03).
     */
    @Transactional
    public OrderView submit(long customerId, long customerUserId, OrderDraft draft) {
        Optional<ShipmentOrder> existing = orders.findByIdempotencyKey(draft.idempotencyKey());
        if (existing.isPresent()) {
            return view(existing.get());
        }
        CustomerView customer = customers.find(customerId).orElseThrow();
        if (!customer.canOrder()) {
            throw new RuleViolationException("Solo un cliente approvato può inviare ordini.");
        }
        if (!draft.isComplete()) {
            throw new RuleViolationException("L'ordine non è completo.");
        }
        Instant now = clock.instant();
        ShipmentOrder order = new ShipmentOrder(
                customerId, tempCode(), draft.idempotencyKey(), now, sender(customer), party(draft.recipient()));
        String packages = ItalianFormats.trim(draft.packageCount());
        order.setDetails(
                ItalianFormats.trim(draft.transportDocumentCode()),
                packages.isEmpty() ? null : Integer.valueOf(packages),
                draft.accessoryServiceId(),
                ItalianFormats.trim(draft.notes()).isEmpty() ? null : ItalianFormats.trim(draft.notes()));
        for (OrderDraft.Line line : draft.lines()) {
            order.addLine(
                    line.kind(), line.heightCm(), line.lengthCm(), line.widthCm(), line.weightKg(), line.quantity());
        }
        order = orders.save(order);
        OrderView view = view(order);
        notifications.enqueue(mails.orderReceived(view));
        notifications.enqueue(mails.newOrder(view));
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "order.submit")
                .entity("shipment_order", order.getId(), order.getTempCode())
                .change(
                        null,
                        Map.of(
                                "status", order.getStatus().name(),
                                "lines", order.getLines().size(),
                                "totalWeightKg", order.getTotalWeightKg(),
                                "totalVolumeM3", order.getTotalVolumeM3())));
        events.publish(new DomainEvent.OrderCreated(order.getId(), customerId));
        return view;
    }

    /** CUS-05: the customer's orders, newest first. */
    @Transactional(readOnly = true)
    public List<OrderView> listForCustomer(long customerId) {
        return orders.findByCustomerIdOrderBySubmittedAtDesc(customerId).stream()
                .map(this::view)
                .toList();
    }

    /** CUS-05: the customer's order with this code or temporary code; empty for another customer's order. */
    @Transactional(readOnly = true)
    public Optional<OrderView> findForCustomer(long customerId, String code) {
        String key = ItalianFormats.trim(code).toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return orders.findByCustomerIdAndAnyCode(customerId, key).map(this::view);
    }

    @Transactional(readOnly = true)
    public Optional<OrderView> find(long orderId) {
        return orders.findById(orderId).map(this::view);
    }

    /**
     * ADM-05: the back-office list, newest first, paginated server-side, with the optional
     * filters of {@link OrderSearch}; the date range is inclusive in Europe/Rome days.
     */
    @Transactional(readOnly = true)
    public Page<OrderView> search(OrderSearch search, int page, int size) {
        Specification<ShipmentOrder> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (search.status() != null) {
                where.add(cb.equal(root.get("status"), search.status()));
            }
            if (search.trackingStateId() != null) {
                where.add(cb.equal(
                        root.get("trackingStateId"), search.trackingStateId().shortValue()));
            }
            if (search.undelivered()) {
                where.add(cb.notEqual(root.get("trackingStateId"), TrackingState.CONSEGNATO));
            }
            if (search.customerId() != null) {
                where.add(cb.equal(root.get("customerId"), search.customerId()));
            }
            if (search.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), startOfDay(search.from())));
            }
            if (search.to() != null) {
                where.add(cb.lessThan(
                        root.get("submittedAt"), startOfDay(search.to().plusDays(1))));
            }
            String code = ItalianFormats.trim(search.code()).toLowerCase(Locale.ROOT);
            if (!code.isEmpty()) {
                String like = like(code);
                where.add(cb.or(
                        cb.like(cb.lower(root.get("code")), like, '\\'),
                        cb.like(cb.lower(root.get("tempCode")), like, '\\')));
            }
            // ADR 0035 (admin.dashboard): one text in the codes, the recipient, the city, the DDT and the customer
            String text = ItalianFormats.trim(search.text()).toLowerCase(Locale.ROOT);
            if (!text.isEmpty()) {
                String like = like(text);
                List<Predicate> any = new ArrayList<>();
                for (String field : List.of(
                        "code", "tempCode", "recipientName", "recipientCity", "transportDocumentCode", "senderName")) {
                    any.add(cb.like(cb.lower(root.get(field)), like, '\\'));
                }
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Pageable pageable =
                PageRequest.of(Math.max(0, page), size, Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id")));
        return orders.findAll(spec, pageable).map(this::view);
    }

    /** {@code %text%} for a LIKE with {@code \\} as the escape character. */
    private static String like(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** The approved order with this definitive code (trimmed, ignoring case): the tracking page (ADM-06). */
    @Transactional(readOnly = true)
    public Optional<OrderView> findApprovedByCode(String code) {
        String key = ItalianFormats.trim(code).toLowerCase(Locale.ROOT);
        return key.isEmpty() ? Optional.empty() : orders.findApprovedByCode(key).map(this::view);
    }

    /**
     * ADM-09: the approved orders of one customer submitted between {@code from} and {@code to}
     * (inclusive, Europe/Rome days), optionally only the delivered ones, oldest first. Never
     * another customer's order.
     */
    @Transactional(readOnly = true)
    public List<OrderView> approvedForReport(long customerId, LocalDate from, LocalDate to, boolean onlyDelivered) {
        Specification<ShipmentOrder> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("customerId"), customerId));
            where.add(cb.equal(root.get("status"), OrderStatus.APPROVED));
            where.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), startOfDay(from)));
            where.add(cb.lessThan(root.get("submittedAt"), startOfDay(to.plusDays(1))));
            if (onlyDelivered) {
                where.add(cb.equal(root.get("trackingStateId"), TrackingState.CONSEGNATO));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return orders.findAll(spec, Sort.by(Sort.Order.asc("submittedAt"), Sort.Order.asc("id"))).stream()
                .map(this::view)
                .toList();
    }

    /** Erasure (NFR-03): removes the staff notes of every order of the customer (the orders stay with their snapshots). */
    @Transactional
    public int eraseInternalNotes(long customerId) {
        return notes.deleteByCustomerId(customerId);
    }

    /** Every order of a customer, oldest first (the export tool, NFR-03). */
    @Transactional(readOnly = true)
    public List<OrderView> allForCustomer(long customerId) {
        return orders.findByCustomerIdOrderByIdAsc(customerId).stream()
                .map(this::view)
                .toList();
    }

    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(ItalianFormats.ROME).toInstant();
    }

    /** ADM-04: the orders waiting for approval, oldest first. */
    @Transactional(readOnly = true)
    public List<OrderView> pendingApproval() {
        return orders.findByStatusOrderBySubmittedAtAsc(OrderStatus.PENDING).stream()
                .map(this::view)
                .toList();
    }

    /**
     * ADM-04 approve: {@code codeText} trimmed, non-empty, unique ignoring case (Q-06);
     * {@code amountText} a euro amount > 0 with comma or point (12c §4.2). Writes the PRESA
     * tracking event (source APPROVAL) and queues EML-05.
     */
    @Transactional
    public OrderView approve(long orderId, long staffUserId, String codeText, String amountText) {
        return approve(orderId, staffUserId, null, codeText, amountText);
    }

    /**
     * ADM-04 approve on the version of the order the staff member saw ({@code expectedVersion},
     * null = no check): an order the customer changed in the meantime (CUS-07) is refused, so no
     * amount is set on lines nobody looked at.
     */
    @Transactional
    public OrderView approve(
            long orderId, long staffUserId, Integer expectedVersion, String codeText, String amountText) {
        ShipmentOrder order = orders.findById(orderId).orElseThrow();
        if (expectedVersion != null) {
            requireVersion(order, expectedVersion);
        }
        String code = validCode(codeText, null);
        BigDecimal amount = validAmount(amountText);
        Instant now = clock.instant();
        order.approve(code, amount, rules.amount().vatIncludedDefault(), staffUserId, now, TrackingState.PRESA); // Q-05
        trackingEvents.save(
                new TrackingEvent(order.getId(), TrackingState.PRESA, now, TrackingEvent.SOURCE_APPROVAL, staffUserId));
        OrderView view = view(order);
        notifications.enqueue(mails.approved(view));
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.approve")
                .entity("shipment_order", order.getId(), code)
                .change(
                        Map.of("status", OrderStatus.PENDING.name()),
                        Map.of("status", OrderStatus.APPROVED.name(), "code", code, "amount", amount)));
        events.publish(new DomainEvent.OrderApproved(order.getId(), staffUserId));
        events.publish(new DomainEvent.TrackingChanged(order.getId(), TrackingState.PRESA, staffUserId));
        return view;
    }

    /** ADM-04 decline: DECLINED + EML-05 (decline text quoting the temporary code). */
    @Transactional
    public OrderView decline(long orderId, long staffUserId, String reason) {
        return decline(orderId, staffUserId, null, reason);
    }

    /** ADM-04 decline on the version the staff member saw (null = no check), as {@link #approve(long, long, Integer, String, String)}. */
    @Transactional
    public OrderView decline(long orderId, long staffUserId, Integer expectedVersion, String reason) {
        ShipmentOrder order = orders.findById(orderId).orElseThrow();
        if (expectedVersion != null) {
            requireVersion(order, expectedVersion);
        }
        order.decline(staffUserId, clock.instant(), reason);
        OrderView view = view(order);
        notifications.enqueue(mails.declined(view));
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.decline")
                .entity("shipment_order", order.getId(), order.getTempCode())
                .change(Map.of("status", OrderStatus.PENDING.name()), Map.of("status", OrderStatus.DECLINED.name())));
        events.publish(new DomainEvent.OrderDeclined(order.getId(), staffUserId));
        return view;
    }

    /** Q-12 seam: SELF_SERVICE (owner, 2026-10-25) lets the customer change and cancel its pending orders (CUS-07); OFFICE_ONLY keeps changes with the office. */
    public boolean customerSelfService() {
        return rules.customerChanges() == OrdersProperties.CustomerChanges.SELF_SERVICE; // Q-12
    }

    /**
     * CUS-07: the customer replaces the lines, the shipment data and the recipient of its own
     * PENDING order, on the version it opened. Refused when changes stay with the office (Q-12),
     * once the office decided, or when the order changed meanwhile. The office gets a notice
     * (EML-06); the audit log keeps the values before and after (personal ones apart).
     */
    @Transactional
    public OrderView updateByCustomer(
            long customerId, long customerUserId, String code, int expectedVersion, OrderContent content) {
        ShipmentOrder order = customerOrder(customerId, code);
        requireCustomerMayChange(order);
        requireVersion(order, expectedVersion);
        AuditEntry entry = AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "order.update")
                .entity("shipment_order", order.getId(), order.getTempCode());
        apply(order, content, ShipmentOrder.BY_CUSTOMER, entry);
        OrderView view = view(order);
        notifications.enqueue(mails.changedByCustomer(view));
        audit.record(entry);
        events.publish(new DomainEvent.OrderChanged(order.getId()));
        return view;
    }

    /** CUS-07: the customer withdraws its own PENDING order (status CANCELLED, nothing deleted); the office gets a notice (EML-06). */
    @Transactional
    public OrderView cancelByCustomer(long customerId, long customerUserId, String code, int expectedVersion) {
        ShipmentOrder order = customerOrder(customerId, code);
        requireCustomerMayChange(order);
        requireVersion(order, expectedVersion);
        order.cancel(ShipmentOrder.BY_CUSTOMER, null, null, clock.instant());
        OrderView view = view(order);
        notifications.enqueue(mails.cancelledByCustomer(view));
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "order.cancel")
                .entity("shipment_order", order.getId(), order.getTempCode())
                .change(Map.of("status", OrderStatus.PENDING.name()), Map.of("status", OrderStatus.CANCELLED.name())));
        events.publish(new DomainEvent.OrderCancelled(order.getId()));
        return view;
    }

    /**
     * ADM-11: staff enter an order for an ACTIVE customer (a phone or e-mail request): a PENDING
     * order with a temporary code and the customer as sender, source BACKOFFICE, then approved
     * as any other (ADM-04). {@code formKey} (one per form) makes a repeated submission harmless.
     * No e-mail: the customer receives the confirmation at approval (EML-05).
     */
    @Transactional
    public OrderView createByStaff(long staffUserId, long customerId, UUID formKey, OrderContent content) {
        if (formKey != null) {
            Optional<ShipmentOrder> existing = orders.findByIdempotencyKey(formKey);
            if (existing.isPresent()) {
                return view(existing.get());
            }
        }
        CustomerView customer = customers
                .find(customerId)
                .filter(c -> !c.anonymised())
                .orElseThrow(() -> new RuleViolationException("Scegli il cliente mittente."));
        if (!customer.canOrder()) {
            throw new RuleViolationException("Il cliente non è attivo: approvalo prima di inserire un ordine.");
        }
        ShipmentOrder order = new ShipmentOrder(
                customerId,
                tempCode(),
                formKey,
                clock.instant(),
                sender(customer),
                party(content.recipient()),
                ShipmentOrder.SOURCE_BACKOFFICE);
        order.setDetails(
                content.transportDocumentCode(), content.packageCount(), content.accessoryServiceId(), content.notes());
        for (OrderDraft.Line line : content.lines()) {
            order.addLine(
                    line.kind(), line.heightCm(), line.lengthCm(), line.widthCm(), line.weightKg(), line.quantity());
        }
        order = orders.save(order);
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.create")
                .entity("shipment_order", order.getId(), order.getTempCode())
                .change(null, facts(order)));
        events.publish(new DomainEvent.OrderCreated(order.getId(), customerId));
        return view(order);
    }

    /**
     * ADM-11: staff change the lines, shipment data and recipient of an order on the version they
     * opened — a PENDING one for every staff member, an APPROVED one only as ADMIN (chapter 08
     * §2.4: corrections after approval are audited ADMIN actions), who may also correct the
     * definitive code (unique, Q-06) and the amount (> 0). DECLINED and CANCELLED orders are closed.
     * No e-mail (ADR 0028).
     */
    @Transactional
    public OrderView updateByStaff(
            long orderId,
            long staffUserId,
            boolean admin,
            int expectedVersion,
            OrderContent content,
            ApprovalCorrection correction) {
        ShipmentOrder order = orders.findById(orderId).orElseThrow();
        requireStaffMayChange(order, admin);
        requireVersion(order, expectedVersion);
        AuditEntry entry = AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.update")
                .entity("shipment_order", order.getId(), order.displayCode());
        if (order.getStatus() == OrderStatus.APPROVED && correction != null) {
            String code = validCode(correction.code(), order.getId());
            BigDecimal amount = validAmount(correction.amount());
            Map<String, Object> before =
                    new LinkedHashMap<>(Map.of("code", order.getCode(), "amount", order.getAmount()));
            order.correctApproval(code, amount);
            entry.change(before, Map.of("code", code, "amount", amount));
        }
        apply(order, content, ShipmentOrder.BY_STAFF, entry);
        audit.record(entry);
        events.publish(new DomainEvent.OrderChanged(order.getId()));
        return view(order);
    }

    /**
     * ADM-11: staff cancel an order on the version they opened — a PENDING one (the customer
     * withdrew it by phone; «Rifiuta» remains the office's refusal, with its e-mail) or, as
     * ADMIN, an APPROVED one (public tracking then answers as for an unknown code). Nothing is
     * deleted; no e-mail (ADR 0028).
     */
    @Transactional
    public OrderView cancelByStaff(long orderId, long staffUserId, boolean admin, int expectedVersion, String reason) {
        ShipmentOrder order = orders.findById(orderId).orElseThrow();
        requireStaffMayChange(order, admin);
        requireVersion(order, expectedVersion);
        String text = ItalianFormats.trim(reason);
        if (text.length() > 500) {
            throw new RuleViolationException("Il motivo può avere al massimo 500 caratteri.");
        }
        String previous = order.getStatus().name();
        order.cancel(ShipmentOrder.BY_STAFF, staffUserId, text, clock.instant());
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.cancel")
                .entity("shipment_order", order.getId(), order.displayCode())
                .change(Map.of("status", previous), Map.of("status", OrderStatus.CANCELLED.name()))
                .personal(null, text.isEmpty() ? null : Map.of("reason", text)));
        events.publish(new DomainEvent.OrderCancelled(order.getId()));
        return view(order);
    }

    /** The orders waiting for approval (the back-office navigation and home). */
    @Transactional(readOnly = true)
    public long countPendingApproval() {
        return orders.countByStatus(OrderStatus.PENDING);
    }

    /** The approved orders not delivered yet: the shipments whose tracking staff still follow (the back-office home). */
    @Transactional(readOnly = true)
    public long countInTransit() {
        return orders.countByStatusAndTrackingStateIdNot(OrderStatus.APPROVED, TrackingState.CONSEGNATO);
    }

    /**
     * ADM-06: sets the tracking state of an approved order (any of the six may follow any other);
     * one event per change, none when the state is set again; EML-09 only when it becomes
     * CONSEGNATO.
     */
    @Transactional
    public OrderView setTrackingState(long orderId, long staffUserId, int stateId) {
        ShipmentOrder order = orders.findById(orderId).orElseThrow();
        TrackingCatalog.StateView state =
                catalog.state(stateId).orElseThrow(() -> new RuleViolationException("Stato di tracking sconosciuto."));
        applyTrackingState(order, state, clock.instant(), TrackingEvent.SOURCE_STAFF, staffUserId);
        return view(order);
    }

    /**
     * One change of tracking state (ADM-06, ENH-15): the event, EML-09 at CONSEGNATO or the ENH-05
     * update, the audit entry and the domain event; nothing when the order is already in the state.
     * Returns whether the state changed.
     */
    private boolean applyTrackingState(
            ShipmentOrder order, TrackingCatalog.StateView state, Instant now, String source, long staffUserId) {
        short previous = order.getTrackingStateId();
        if (!order.setTrackingState(state.id(), now)) {
            return false;
        }
        trackingEvents.save(new TrackingEvent(order.getId(), state.id(), now, source, staffUserId));
        if (state.id() == TrackingState.CONSEGNATO) {
            notifications.enqueue(mails.delivered(view(order)));
        } else {
            trackingUpdates.mailFor(view(order), state).ifPresent(notifications::enqueue); // ENH-05
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("trackingStateId", state.id());
        if (TrackingEvent.SOURCE_BULK.equals(source)) {
            after.put("source", source);
        }
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.tracking")
                .entity("shipment_order", order.getId(), order.getCode())
                .change(Map.of("trackingStateId", previous), after));
        events.publish(new DomainEvent.TrackingChanged(order.getId(), state.id(), staffUserId));
        return true;
    }

    /** ENH-15 (ADR 0035): the codes of a pasted list, the approved orders they name, the codes that name none. */
    public record BulkSelection(List<OrderView> orders, List<String> notFound) {}

    /** ENH-15: what a bulk change did — the orders whose state changed and those already in it. */
    public record BulkResult(List<OrderView> changed, List<OrderView> unchanged) {}

    /**
     * ENH-15: the approved orders named by these codes (trimmed, ignoring case, each once, in the
     * order typed) and the codes that name none — unknown, or a temporary code of an order not yet
     * confirmed: tracking is for confirmed orders only (ADR 0008).
     */
    @Transactional(readOnly = true)
    public BulkSelection approvedByCodes(List<String> codes) {
        List<OrderView> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (String code : codes) {
            Optional<ShipmentOrder> order =
                    orders.findApprovedByCode(ItalianFormats.trim(code).toLowerCase(Locale.ROOT));
            if (order.isEmpty()) {
                notFound.add(code);
            } else if (seen.add(order.get().getId())) {
                found.add(view(order.get()));
            }
        }
        return new BulkSelection(found, notFound);
    }

    /**
     * ENH-15: one tracking state on many approved orders, in one transaction and at one time: an
     * event (source BULK) by the same staff user for every order whose state changes, with the
     * e-mails and the audit of {@link #setTrackingState}; nothing for an order already in the
     * state. An order that is no longer approved refuses the whole change.
     */
    @Transactional
    public BulkResult setTrackingStates(List<Long> orderIds, long staffUserId, int stateId) {
        TrackingCatalog.StateView state =
                catalog.state(stateId).orElseThrow(() -> new RuleViolationException("Stato di tracking sconosciuto."));
        Instant now = clock.instant();
        List<OrderView> changed = new ArrayList<>();
        List<OrderView> unchanged = new ArrayList<>();
        for (Long id : orderIds) {
            ShipmentOrder order = orders.findById(id)
                    .filter(o -> o.getStatus() == OrderStatus.APPROVED)
                    .orElseThrow(() -> new RuleViolationException(
                            "Un ordine dell'elenco non è più confermato: ricontrolla i codici."));
            if (applyTrackingState(order, state, now, TrackingEvent.SOURCE_BULK, staffUserId)) {
                changed.add(view(order));
            } else {
                unchanged.add(view(order));
            }
        }
        return new BulkResult(changed, unchanged);
    }

    /**
     * ADR 0035 «Oggi» (admin.dashboard): the confirmed orders whose goods are still to pick up —
     * tracking «Presa in carico» (or «Non gestito») —, confirmed longest ago first.
     */
    @Transactional(readOnly = true)
    public Page<OrderView> toPickUp(int size) {
        Specification<ShipmentOrder> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), OrderStatus.APPROVED),
                root.get("trackingStateId").in((short) TrackingState.NON_GESTITO, TrackingState.PRESA));
        return orders.findAll(spec, PageRequest.of(0, size, Sort.by("approvedAt", "id")))
                .map(this::view);
    }

    /**
     * ADR 0035 «Oggi»: the confirmed orders on their way (in transit, at the destination terminal,
     * out for delivery) that were confirmed before {@code before}, longest ago first.
     */
    @Transactional(readOnly = true)
    public Page<OrderView> travellingSince(Instant before, int size) {
        Specification<ShipmentOrder> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), OrderStatus.APPROVED),
                root.get("trackingStateId").in((short) 2, (short) 3, (short) 4),
                cb.lessThan(root.get("approvedAt"), before));
        return orders.findAll(spec, PageRequest.of(0, size, Sort.by("approvedAt", "id")))
                .map(this::view);
    }

    /** ADM-06: the tracking history of an order, oldest first. */
    @Transactional(readOnly = true)
    public List<OrderView.TrackingChange> trackingHistory(long orderId) {
        return trackingEvents.findByOrderIdOrderByOccurredAtAscIdAsc(orderId).stream()
                .map(e -> new OrderView.TrackingChange(
                        e.getStateId(),
                        label(e.getStateId()),
                        catalog.state(e.getStateId())
                                .map(TrackingCatalog.StateView::publicTitle)
                                .orElse("-"),
                        e.getOccurredAt(),
                        e.getSource()))
                .toList();
    }

    /** ADM-07: the staff note, separate from the customer's note, with the before/after text in the audit detail. */
    @Transactional
    public void setInternalNote(long orderId, long staffUserId, String body) {
        orders.findById(orderId).orElseThrow();
        String text = ItalianFormats.trim(body);
        if (text.length() > 5000) {
            throw new RuleViolationException("La nota può avere al massimo 5000 caratteri.");
        }
        Instant now = clock.instant();
        OrderInternalNote note = notes.findById(orderId).orElse(null);
        String before = note == null ? null : note.getBody();
        if (note == null) {
            if (text.isEmpty()) {
                return;
            }
            notes.save(new OrderInternalNote(orderId, text, staffUserId, now));
        } else if (text.isEmpty()) {
            notes.delete(note);
        } else {
            note.update(text, staffUserId, now);
        }
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.note")
                .entity("shipment_order", orderId, null)
                .personal(
                        before == null ? null : Map.of("note", before), text.isEmpty() ? null : Map.of("note", text)));
    }

    @Transactional(readOnly = true)
    public Optional<String> internalNote(long orderId) {
        return notes.findById(orderId).map(OrderInternalNote::getBody);
    }

    /** The definitive code typed by staff (ADM-04, ADM-11): trimmed, at most {@value #CODE_MAX} characters, unique ignoring case (Q-06). */
    private String validCode(String codeText, Long exceptOrderId) {
        String code = ItalianFormats.trim(codeText);
        if (code.isEmpty()) {
            throw new RuleViolationException("Inserisci il codice della spedizione.");
        }
        if (code.length() > CODE_MAX) {
            throw new RuleViolationException("Il codice può avere al massimo " + CODE_MAX + " caratteri.");
        }
        String normalised = code.toLowerCase(Locale.ROOT);
        boolean taken = exceptOrderId == null
                ? orders.existsByCodeNormalised(normalised)
                : orders.existsByCodeNormalisedAndIdNot(normalised, exceptOrderId);
        if (taken) {
            throw new RuleViolationException("E' già stata effettuata una registrazione con questo codice."
                    + " Si prega di riprovare con un nuovo codice.");
        }
        return code;
    }

    /** The amount for the customer (12c §4.2): a euro amount > 0 with at most two decimals, comma or point. */
    private static BigDecimal validAmount(String amountText) {
        return ItalianFormats.parseDecimal(amountText)
                .filter(a -> a.signum() > 0 && a.stripTrailingZeros().scale() <= 2)
                .orElseThrow(
                        () -> new RuleViolationException("Inserisci un importo maggiore di zero, per esempio 180,50."))
                .setScale(2);
    }

    private ShipmentOrder customerOrder(long customerId, String code) {
        String key = ItalianFormats.trim(code).toLowerCase(Locale.ROOT);
        return orders.findByCustomerIdAndAnyCode(customerId, key)
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private void requireCustomerMayChange(ShipmentOrder order) {
        if (!customerSelfService()) {
            throw new RuleViolationException("Per modificare o annullare l'ordine contatta il centro logistico.");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new RuleViolationException(
                    "Il centro logistico ha già gestito l'ordine: non si può più modificare né annullare.");
        }
    }

    private static void requireStaffMayChange(ShipmentOrder order, boolean admin) {
        switch (order.getStatus()) {
            case PENDING -> {}
            case APPROVED -> {
                if (!admin) {
                    throw new RuleViolationException(
                            "Solo un amministratore può modificare o annullare un ordine già confermato.");
                }
            }
            default -> throw new RuleViolationException("L'ordine è chiuso: non si può più modificare né annullare.");
        }
    }

    /** Optimistic check of the page the change comes from: the order must still be the one that was shown. */
    private static void requireVersion(ShipmentOrder order, int expectedVersion) {
        if (order.getVersion() != expectedVersion) {
            throw new RuleViolationException(
                    "L'ordine è stato modificato nel frattempo: controlla i dati aggiornati e riprova.");
        }
    }

    /** Applies an edit and records its before/after values on {@code entry} (personal ones apart). */
    private void apply(ShipmentOrder order, OrderContent content, String byType, AuditEntry entry) {
        Map<String, Object> before = facts(order);
        Map<String, Object> personalBefore = personalFacts(order);
        order.setDetails(
                content.transportDocumentCode(), content.packageCount(), content.accessoryServiceId(), content.notes());
        order.setRecipient(party(content.recipient()));
        order.replaceLines(content.lines());
        order.edited(byType, clock.instant());
        Map<String, Object> after = facts(order);
        Map<String, Object> merged = new LinkedHashMap<>(before);
        Map<String, Object> mergedAfter = new LinkedHashMap<>(after);
        if (entry.before() != null) {
            merged.putAll(entry.before());
        }
        if (entry.after() != null) {
            mergedAfter.putAll(entry.after());
        }
        entry.change(merged, mergedAfter).personal(personalBefore, personalFacts(order));
    }

    /** The non-personal facts of an order for the audit log. */
    private static Map<String, Object> facts(ShipmentOrder order) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("status", order.getStatus().name());
        facts.put("lines", order.getLines().size());
        facts.put("totalWeightKg", order.getTotalWeightKg());
        facts.put("totalVolumeM3", order.getTotalVolumeM3());
        facts.put("transportDocumentCode", order.getTransportDocumentCode());
        facts.put("packageCount", order.getPackageCount() == null ? "-" : order.getPackageCount());
        facts.put("accessoryServiceId", order.getAccessoryServiceId() == null ? 0 : order.getAccessoryServiceId());
        facts.put("source", order.getSource());
        return facts;
    }

    /** The personal values of an order (recipient, notes), stored apart in the audit detail. */
    private static Map<String, Object> personalFacts(ShipmentOrder order) {
        ShipmentOrder.Party r = order.recipient();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("recipient", r.name() + ", " + r.addressOneLine());
        facts.put("recipientPhone", r.phone() == null ? "-" : r.phone());
        facts.put("recipientEmail", r.email() == null ? "-" : r.email());
        facts.put("notes", order.getCustomerNotes() == null ? "-" : order.getCustomerNotes());
        return facts;
    }

    /** The sender snapshot (Q-13): the customer's registered data at the time of the order. */
    private static ShipmentOrder.Party sender(CustomerView customer) {
        return new ShipmentOrder.Party(
                customer.companyName(),
                customer.addressLine(),
                customer.province(),
                customer.city(),
                customer.postalCode(),
                customer.phone(),
                customer.email());
    }

    private static ShipmentOrder.Party party(OrderDraft.Recipient r) {
        return new ShipmentOrder.Party(
                r.name(), r.addressLine(), r.province(), r.city(), r.postalCode(), r.phone(), r.email());
    }

    /** ADM-11: the definitive code and the amount an ADMIN may correct on an approved order (raw text, validated as at approval). */
    public record ApprovalCorrection(String code, String amount) {}

    OrderView view(ShipmentOrder o) {
        return new OrderView(
                o.getId(),
                o.getCustomerId(),
                o.getStatus(),
                o.getTempCode(),
                o.getCode(),
                o.getTransportDocumentCode(),
                o.getPackageCount(),
                o.getAccessoryServiceId(),
                o.getAccessoryServiceId() == null
                        ? catalog.activeServices().stream()
                                .filter(TrackingCatalog.ServiceView::none)
                                .map(TrackingCatalog.ServiceView::label)
                                .findFirst()
                                .orElse("-")
                        : catalog.service(o.getAccessoryServiceId())
                                .map(TrackingCatalog.ServiceView::label)
                                .orElse("-"),
                o.getCustomerNotes(),
                o.getAmount(),
                o.getAmountVatIncluded(),
                o.getTotalWeightKg(),
                o.getTotalVolumeM3(),
                o.getTrackingStateId(),
                label(o.getTrackingStateId()),
                o.getTrackingUpdatedAt(),
                o.sender(),
                o.recipient(),
                o.getSubmittedAt(),
                o.getApprovedAt(),
                o.getDeclinedAt(),
                o.getDeclineReason(),
                o.getLines().stream()
                        .map(l -> new OrderView.Line(
                                l.getLineNo(),
                                l.getKind(),
                                l.getHeightCm(),
                                l.getLengthCm(),
                                l.getWidthCm(),
                                l.getWeightKg(),
                                l.getQuantity(),
                                l.getLineWeightKg(),
                                l.getLineVolumeM3()))
                        .toList(),
                o.getVersion(),
                o.getSource(),
                o.getCancelledAt(),
                o.getCancelledByType(),
                o.getCancelReason(),
                o.getEditedAt(),
                o.getEditedByType());
    }

    private String label(short stateId) {
        return catalog.state(stateId).map(TrackingCatalog.StateView::label).orElse("-");
    }

    /** {@code TEMP-} plus 8 random base-32 characters (chapter 08 §2.5): keeps the legacy marker, cannot be guessed. */
    static String tempCode() {
        StringBuilder sb = new StringBuilder("TEMP-");
        for (int i = 0; i < 8; i++) {
            sb.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
        }
        return sb.toString();
    }
}
