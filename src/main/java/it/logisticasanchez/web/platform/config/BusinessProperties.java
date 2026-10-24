package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Business configuration: the single source of the company data shown on the site, in e-mails,
 * JSON-LD, {@code config.js}, error pages and {@code security.txt} (ADR 0027, PUB-02). The
 * defaults are the public company data of chapter 01 "The company" and chapter 06 §7.1; every
 * value can be overridden from the environment ({@code LS_BUSINESS_*}).
 */
@Validated
@ConfigurationProperties(prefix = "ls.business")
public record BusinessProperties(
        /** Legal name, as printed in the footer and the privacy page. */
        @NotBlank @DefaultValue("Logistica Sanchez S.r.l.") String legalName,
        /** Brand name used in copy and e-mail subjects (Q-37: «Logistica Sanchez»). */
        @NotBlank @DefaultValue("Logistica Sanchez") String brandName,
        @NotBlank @DefaultValue("Il pallet espresso") String slogan,
        /** VAT number without the country prefix; JSON-LD prints {@code IT} + this value. */
        @NotBlank @Pattern(regexp = "[0-9]{11}") @DefaultValue("13113080967")
        String vatNumber,

        @DefaultValue Address address,
        /** One official phone number, E.164 with spaces allowed (Q-15: one number, not two). */
        @NotBlank @DefaultValue("+39 329 239 6962") String phone, // Q-15
        /** WhatsApp number in {@code wa.me} form (digits only). Q-15: the same official number. */
        @NotBlank @Pattern(regexp = "[0-9]{8,15}") @DefaultValue("393292396962")
        String whatsapp, // Q-15
        /** The office mailbox that receives EML-06/07/08 and answers customers (Q-43). */
        @NotBlank @DefaultValue("logisticasanchezmb@gmail.com")
        String officeEmail, // Q-43

        @DefaultValue Hours hours,
        @DefaultValue Social social,
        /** Canonical origin of the public site, without a trailing slash (chapter 06 §6.1). */
        @NotBlank @Pattern(regexp = "https?://[^/\\s]+") @DefaultValue("https://logisticasanchez.com")
        String siteUrl) {

    /** Registered address and hub (chapter 01). */
    public record Address(
            @NotBlank @DefaultValue("Via dei Meiani, 90") String line,
            @NotBlank @DefaultValue("20831") String postalCode,
            @NotBlank @DefaultValue("Seregno") String city,
            @NotBlank @DefaultValue("MB") String province,
            @NotBlank @DefaultValue("IT") String country) {

        /** «Via dei Meiani, 90 – 20831 Seregno (MB)», the form used across the site. */
        public String oneLine() {
            return line + " – " + postalCode + " " + city + " (" + province + ")";
        }
    }

    /** Office hours as shown in the partials and the contacts table (chapter 06 §7.1). */
    public record Hours(
            @NotBlank @DefaultValue("8:00–18:00") String weekdays,
            @NotBlank @DefaultValue("8:00–12:00") String saturday,
            /** The short form of the header: «Lun–Ven 8–18 · Sab 8–12». */
            @NotBlank @DefaultValue("Lun–Ven 8–18 · Sab 8–12")
            String shortLabel) {}

    public record Social(
            @DefaultValue("https://www.instagram.com/sanchez_logistica_mb")
            String instagram) {}

    /** The phone number in {@code tel:} form: digits with the leading plus. */
    public String phoneDigits() {
        return "+" + phone.replaceAll("[^0-9]", "");
    }

    public String whatsappUrl() {
        return "https://wa.me/" + whatsapp;
    }

    /** VAT id with the country prefix, as JSON-LD {@code vatID} prints it. */
    public String vatId() {
        return address.country() + vatNumber;
    }
}
