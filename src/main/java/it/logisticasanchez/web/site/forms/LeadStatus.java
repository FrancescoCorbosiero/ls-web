package it.logisticasanchez.web.site.forms;

import java.util.Arrays;
import java.util.List;

/**
 * The states of a website request (chapter 08 §2.4): {@code NEW → CONTACTED → WON | LOST};
 * {@code NEW | CONTACTED → SPAM}. WON, LOST and SPAM are final. An order made from the request
 * (ENH-06, ADR 0036) marks it WON from NEW as well: the only move that skips CONTACTED.
 */
public enum LeadStatus {
    NEW,
    CONTACTED,
    WON,
    LOST,
    SPAM;

    /** Whether staff may move a request from this state to {@code target} (a different state). */
    public boolean canMoveTo(LeadStatus target) {
        return switch (this) {
            case NEW -> target == CONTACTED || target == SPAM;
            case CONTACTED -> target == WON || target == LOST || target == SPAM;
            case WON, LOST, SPAM -> false;
        };
    }

    /** The states staff may move to from this one, in the order of the buttons. */
    public List<LeadStatus> next() {
        return Arrays.stream(values()).filter(this::canMoveTo).toList();
    }

    /** Still to be handled: an order can be made from it. */
    public boolean open() {
        return this == NEW || this == CONTACTED;
    }
}
