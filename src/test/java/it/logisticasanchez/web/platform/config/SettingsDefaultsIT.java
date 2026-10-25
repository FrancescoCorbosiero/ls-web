package it.logisticasanchez.web.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The typed settings bind with the defaults of chapter 01, chapter 10 §2 and plan §8 (ADR 0027). */
@SpringBootTest
class SettingsDefaultsIT extends AbstractPostgresIT {

    @Autowired
    private BusinessProperties business;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private SiteProperties site;

    @Autowired
    private CustomersProperties customers;

    @Autowired
    private OrdersProperties orders;

    @Autowired
    private RetentionProperties retention;

    @Autowired
    private CompatProperties compat;

    @Test
    void businessConfigurationIsTheCompanyData() {
        assertThat(business.legalName()).isEqualTo("Logistica Sanchez S.r.l.");
        assertThat(business.vatId()).isEqualTo("IT13113080967");
        assertThat(business.phoneDigits()).isEqualTo("+393292396962");
        assertThat(business.whatsappUrl()).isEqualTo("https://wa.me/393292396962");
        assertThat(business.address().oneLine()).isEqualTo("Via dei Meiani, 90 – 20831 Seregno (MB)");
        assertThat(business.hours().shortLabel()).isEqualTo("Lun–Ven 8–18 · Sab 8–12");
        assertThat(business.siteUrl()).isEqualTo("https://logisticasanchez.com");
        assertThat(business.officeEmail()).isNotBlank();
    }

    @Test
    void sessionTokenAndLockoutSeamsCarryTheChapter10Figures() {
        assertThat(security.sessions().customer().idle()).isEqualTo(Duration.ofMinutes(60));
        assertThat(security.sessions().customer().absolute()).isEqualTo(Duration.ofHours(12));
        assertThat(security.sessions().staff().idle()).isEqualTo(Duration.ofMinutes(30));
        assertThat(security.sessions().staff().absolute()).isEqualTo(Duration.ofHours(10));
        assertThat(security.tokens().signIn()).isEqualTo(Duration.ofMinutes(15));
        assertThat(security.tokens().registration()).isEqualTo(Duration.ofHours(24));
        assertThat(security.staff().lockout().threshold()).isEqualTo(10);
        assertThat(security.staff().lockout().duration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(security.staff().totp().enabled()).isFalse();
    }

    @Test
    void moduleSeamsCarryTheChapter11Defaults() {
        assertThat(customers.maxUsersPerCustomer()).isEqualTo(1);
        assertThat(customers.registration().phoneRequired()).isTrue();
        assertThat(customers.approval().requiresVerifiedEmail()).isTrue();
        assertThat(orders.amount().vatIncludedDefault()).isFalse();
        assertThat(orders.transportDocument().required()).isTrue();
        assertThat(orders.lines().warnHeightCm()).isEqualTo(240);
        assertThat(orders.customerChanges()).isEqualTo(OrdersProperties.CustomerChanges.SELF_SERVICE);
        assertThat(retention.outboxBodiesDays()).isEqualTo(30);
        assertThat(retention.loginTokensHoursAfterExpiry()).isEqualTo(24);
        assertThat(compat.cors().allowedOrigins()).containsExactly("https://logisticasanchez.com");
        assertThat(site.hosts().canonical()).isEqualTo("logisticasanchez.com");
        assertThat(site.cache().fontsMaxAge()).isEqualTo(Duration.ofDays(365));
    }
}
