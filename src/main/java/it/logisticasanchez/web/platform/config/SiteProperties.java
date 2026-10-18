package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Public-site settings owned by {@code platform}: the host names the app routes (chapter 10 §3,
 * plan §4.3 "Host routing"), the caching rules of PD-02 and the public-form quantity rule PD-04.
 */
@Validated
@ConfigurationProperties(prefix = "ls.site")
public record SiteProperties(@DefaultValue Hosts hosts, @DefaultValue Cache cache, @DefaultValue Form form) {

    /**
     * Host rules: {@code www} answers 301 to the canonical host keeping path and query; the old
     * order-portal host answers 301 to {@code /area-clienti/}; the old CMS host 301 to {@code
     * /gestione/}; the canonical and {@code app} hosts serve the same app (Q-30, Q-47).
     */
    public record Hosts(
            @NotBlank @DefaultValue("logisticasanchez.com") String canonical,
            @NotBlank @DefaultValue("app.logisticasanchez.com") String app,
            @NotBlank @DefaultValue("www.logisticasanchez.com") String www,
            @NotBlank @DefaultValue("ordini.logisticasanchez.com") String orders,
            @NotBlank @DefaultValue("dashboard.logisticasanchez.com") String dashboard) {}

    /** PD-02: fonts immutable for one year, images one week, HTML/CSS/JS no-cache + ETag. */
    public record Cache(
            @NotNull @DefaultValue("365d") Duration fontsMaxAge, // PD-02
            @NotNull @DefaultValue("7d") Duration imagesMaxAge // PD-02
            ) {}

    /** PD-04: the quote form accepts a decimal comma in the quantity, as the static site did. */
    public record Form(@DefaultValue("false") boolean quantityIntegerOnly // PD-04
            ) {}
}
