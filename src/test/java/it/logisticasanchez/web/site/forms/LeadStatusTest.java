package it.logisticasanchez.web.site.forms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ENH-06: the state machine of a website request, chapter 08 §2.4 (ADR 0036). */
@Tag("ENH-06")
class LeadStatusTest {

    @Test
    void aNewRequestIsContactedOrSpam() {
        assertThat(LeadStatus.NEW.next()).containsExactly(LeadStatus.CONTACTED, LeadStatus.SPAM);
        assertThat(LeadStatus.NEW.canMoveTo(LeadStatus.WON)).isFalse();
        assertThat(LeadStatus.NEW.canMoveTo(LeadStatus.LOST)).isFalse();
        assertThat(LeadStatus.NEW.canMoveTo(LeadStatus.NEW)).isFalse();
    }

    @Test
    void aContactedRequestIsWonLostOrSpamAndNeverNewAgain() {
        assertThat(LeadStatus.CONTACTED.next()).containsExactly(LeadStatus.WON, LeadStatus.LOST, LeadStatus.SPAM);
        assertThat(LeadStatus.CONTACTED.canMoveTo(LeadStatus.NEW)).isFalse();
    }

    @Test
    void wonLostAndSpamAreFinal() {
        for (LeadStatus done : new LeadStatus[] {LeadStatus.WON, LeadStatus.LOST, LeadStatus.SPAM}) {
            assertThat(done.next()).as("%s", done).isEmpty();
            assertThat(done.open()).isFalse();
        }
        assertThat(LeadStatus.NEW.open()).isTrue();
        assertThat(LeadStatus.CONTACTED.open()).isTrue();
    }
}
