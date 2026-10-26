#!/usr/bin/env python3
"""Port the pages of the static site (legacy/ls-home/public/**/*.html) to Thymeleaf templates.

Each page keeps its <head> metadata and its <main> verbatim (PUB-01); only the four partials
become fragment calls (layout/public), and the business data of ADR 0027 (site URL, phone,
WhatsApp, office e-mail, VAT, Instagram) become expressions over ${business} (PUB-02). The
order-portal links become «Area clienti» → /area-clienti/ (chapter 06 §7.1, ADR 0012). Two lists
get an alternative drawn from the accessory services, rendered only with ENH-14 on (ADR 0043).

    python3 tools/port-ls-home-pages.py            # rewrites src/main/resources/templates/site/**
    python3 tools/port-ls-home-pages.py --check    # exit 1 when the templates differ from a fresh port

The templates are committed: the script documents how they were produced and re-ports them
when the legacy checkout changes. Run it only with legacy/ls-home at the commit of PROMPT.md.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PUBLIC = ROOT / "legacy" / "ls-home" / "public"
TEMPLATES = ROOT / "src" / "main" / "resources" / "templates"

SITE = "https://logisticasanchez.com"
PHONE_DIGITS = "+393292396962"
WHATSAPP = "393292396962"
EMAIL = "logisticasanchezmb@gmail.com"
VAT_ID = "IT13113080967"
INSTAGRAM = "https://www.instagram.com/sanchez_logistica_mb"
PHONE_NBSP = "+39&nbsp;329&nbsp;239&nbsp;6962"
PHONE_TEXT = "+39 329 239 6962"
PORTAL = "https://ordini.logisticasanchez.com"

PARTIAL = re.compile(r"<!-- partial:([a-z0-9-]+) -->[\s\S]*?<!-- /partial:\1 -->")


def fragment(name: str, page_key: str) -> str:
    if name == "head":
        return '<th:block th:replace="~{layout/public :: htmlHead(true)}"></th:block>'
    if name == "header":
        return f"<th:block th:replace=\"~{{layout/public :: siteHeader('{page_key}')}}\"></th:block>"
    if name == "cta":
        return '<th:block th:replace="~{layout/public :: cta}"></th:block>'
    if name == "footer":
        return '<th:block th:replace="~{layout/public :: siteFooter}"></th:block>'
    raise SystemExit(f"unknown partial {name}")


def concat_expression(text: str) -> str:
    """A Thymeleaf expression concatenating the literal parts of text with the phone and e-mail settings."""
    parts = re.split("(" + re.escape(PHONE_TEXT) + "|" + re.escape(EMAIL) + ")", text)
    terms = []
    for part in parts:
        if part == PHONE_TEXT:
            terms.append("business.phone")
        elif part == EMAIL:
            terms.append("business.officeEmail")
        elif part:
            terms.append("'" + part.replace("'", "\\'") + "'")
    return "${" + " + ".join(terms) + "}"


def port_jsonld(script: str) -> str:
    """Business data inside a JSON-LD block become JavaScript-inlined expressions."""
    script = re.sub(
        r'"([^"]*(?:' + re.escape(PHONE_TEXT) + "|" + re.escape(EMAIL) + r')[^"]*)"',
        lambda m: "[(${layout.json(" + concat_expression(m.group(1))[2:-1] + ")})]",
        script,
    )
    script = re.sub(
        r'"' + re.escape(SITE) + r'([^"]*)"',
        lambda m: "[(${layout.json(business.siteUrl + '" + m.group(1) + "')})]" if m.group(1) else "[(${layout.json(business.siteUrl)})]",
        script,
    )
    script = script.replace(f'"{PHONE_DIGITS}"', "[(${layout.json(business.phoneDigits())})]")
    script = script.replace(f'"{EMAIL}"', "[(${layout.json(business.officeEmail)})]")
    script = script.replace(f'"{VAT_ID}"', "[(${layout.json(business.vatId())})]")
    script = script.replace(f'"{INSTAGRAM}"', "[(${layout.json(business.social.instagram)})]")
    # Text inlining with layout.json(): Thymeleaf's JavaScript inlining would write "\/" for every slash.
    return script.replace('<script type="application/ld+json">', '<script type="application/ld+json" th:inline="text">', 1)


# Texts added to two legal pages for the customer area and the back office (NFR-03, chapter
# 10 §5; plain text for the owner's review at the next gate). Each entry: page key →
# [(anchor, html inserted before the anchor)]. The same additions are applied by the port, so
# --check stays meaningful for these pages too. Each addition is wrapped in
# <!-- ls-web:addition --> … <!-- /ls-web:addition --> so that SitePagesIT can compare the rest
# of <main> with the legacy page (PUB-01 allow-list, ADR 0012).
ADDITIONS = {
    "privacy": [
        (
            '<li><strong>Dati di navigazione</strong>:',
            "<li><strong>Dati dell'area clienti</strong>, se ti registri per inviare ordini: ragione sociale o nominativo, "
            "indirizzo, telefono, email, partita IVA o codice fiscale, i dati delle spedizioni che inserisci "
            "(destinatari, merce, pesi e misure, documenti di trasporto, note) e i registri degli accessi "
            "(data, ora e indirizzo IP in forma cifrata). L'accesso avviene con un link inviato alla tua email, senza password.</li>\n",
        ),
        (
            '<li><strong>Netlify, Inc.</strong>',
            "<li><strong>Il fornitore che ospita l'applicazione e il database</strong> (sito, area clienti e gestione), "
            "in qualit\u00e0 di responsabile del trattamento.</li>\n",
        ),
        (
            '<li>Dati di navigazione: per il periodo di conservazione dei log del fornitore di hosting.</li>',
            "<li>Dati dell'area clienti: account e dati di registrazione per la durata del rapporto; ordini e spedizioni "
            "per il tempo previsto dalla legge (di norma 10 anni); link di accesso al massimo 24 ore; copia delle email "
            "inviate 30 giorni; registri di sicurezza 30 giorni.</li>\n",
        ),
        (
            '<p>Se ritieni che il trattamento violi la normativa',
            "<p>Per i dati dell'area clienti puoi chiedere all'ufficio una copia completa dei tuoi dati o la loro cancellazione: "
            "la cancellazione avviene anonimizzando i dati che ti riguardano, salvo quelli degli ordini che dobbiamo conservare per legge.</p>\n",
        ),
    ],
    "cookie": [
        (
            '<h2 id="terze-parti">',
            '<h2 id="area-clienti">Cookie tecnici dell\'area clienti e della gestione</h2>\n'
            "<p>Le pagine pubbliche non usano cookie. Quando accedi all'area clienti o alla gestione, il sito imposta un solo "
            "cookie tecnico, necessario per riconoscerti da una pagina all'altra: non richiede consenso (art. 122 Codice privacy).</p>\n"
            '<div class="table-wrap"><table class="table">\n'
            '<caption class="visually-hidden">Cookie dell\'area clienti</caption>\n'
            '<thead><tr><th scope="col">Nome</th><th scope="col">A cosa serve</th><th scope="col">Durata</th></tr></thead>\n'
            "<tbody>\n"
            '<tr><th scope="row"><code>__Host-LS_SESSION</code></th><td>Mantiene la sessione dopo l\'accesso e protegge i moduli da invii non autorizzati</td>'
            "<td>Fino all'uscita o dopo un periodo di inattivit\u00e0; al massimo alcune ore</td></tr>\n"
            "</tbody></table></div>\n\n",
        ),
    ],
}


def port(html: str, page_key: str, error_page: bool) -> str:
    html = html.replace('<!doctype html>\n<html lang="it">', '<!DOCTYPE html>\n<html lang="it" xmlns:th="http://www.thymeleaf.org">', 1)
    if error_page:
        html = PARTIAL.sub(
            lambda m: fragment(m.group(1), page_key).replace("htmlHead(true)", "htmlHead(false)"), html)
    else:
        html = PARTIAL.sub(lambda m: fragment(m.group(1), page_key), html)

    # <head>: canonical, Open Graph and article URLs follow the configured site URL.
    html = re.sub(r'<link rel="canonical" href="' + re.escape(SITE) + r'([^"]*)">',
                  r'<link rel="canonical" th:href="|${business.siteUrl}\1|">', html)
    html = re.sub(r'(<meta property="(?:og:url|og:image)" )content="' + re.escape(SITE) + r'([^"]*)">',
                  r'\1th:content="|${business.siteUrl}\2|">', html)
    html = re.sub(
        r'content="([^"]*(?:' + re.escape(PHONE_TEXT) + "|" + re.escape(EMAIL) + r')[^"]*)"',
        lambda m: 'th:content="' + concat_expression(m.group(1)) + '"',
        html,
    )
    html = re.sub(r'<script type="application/ld\+json">[\s\S]*?</script>', lambda m: port_jsonld(m.group(0)), html)

    # <main>: contact links and texts.
    html = re.sub(r'href="https://wa\.me/' + WHATSAPP + r'([^"]*)"', r'th:href="|${business.whatsappUrl()}\1|"', html)
    html = html.replace(f'href="tel:{PHONE_DIGITS}"', 'th:href="|tel:${business.phoneDigits()}|"')
    html = re.sub(r'href="mailto:' + re.escape(EMAIL) + r'([^"]*)"', r'th:href="|mailto:${business.officeEmail}\1|"', html)
    html = html.replace(f'href="{INSTAGRAM}"', 'th:href="${business.social.instagram}"')
    html = html.replace(f">{PHONE_NBSP}<", ">[[${business.phoneDisplay()}]]<")
    html = html.replace(f">{PHONE_TEXT}<", ">[[${business.phone}]]<")
    html = html.replace(f">{EMAIL}<", ">[[${business.officeEmail}]]<")
    html = html.replace(">logisticasanchezmb@<wbr>gmail.com<", ">[[${business.officeEmail}]]<")

    # Order-portal links → «Area clienti» (chapter 06 §7.1; ADR 0012).
    html = re.sub(r'href="' + re.escape(PORTAL) + r'" target="_blank" rel="noopener"(>(?:<svg[\s\S]*?</svg>)?)Effettua un ordine<',
                  r'href="/area-clienti/"\1Area clienti<', html)
    if PORTAL in html:
        raise SystemExit(f"{page_key}: an order-portal link survived the port")
    return add_texts(html, page_key)


def add_texts(html: str, page_key: str) -> str:
    """Inserts the ADDITIONS of a page before their anchors (each anchor must exist exactly once)."""
    for anchor, text in ADDITIONS.get(page_key, []):
        if html.count(anchor) != 1:
            raise SystemExit(f"{page_key}: anchor not found once: {anchor}")
        html = html.replace(anchor, "<!-- ls-web:addition -->" + text + "<!-- /ls-web:addition -->" + anchor)
    return html


# ENH-14 (flag admin.enumerations, ADR 0043): with the flag on, «Servizi accessori» of /servizi/
# and the extras of the quote wizard are drawn from the accessory services; with it off the model
# attribute is null and the ported block is rendered unchanged (PUB-01, PUB-06). Each entry:
# template key → (start of the ported block, the text that follows its end, the attribute added
# to its opening tag, the block drawn from the enumeration, inserted right after the ported one).
ENUMERATION_BLOCKS = {
    "servizi": (
        '<div class="features features-3" data-stagger>',
        "\n    </div>\n  </section>",
        'th:if="${siteAccessories == null}"',
        '<div class="features features-3" data-stagger th:if="${siteAccessories != null}" data-accessories>'
        '<div class="feature" th:each="s : ${siteAccessories}">\n'
        '          <span class="feature-ico"><svg class="icon" aria-hidden="true"><use th:href="|/img/icons.svg#${s.icon}|"/></svg></span>\n'
        '          <h3 th:text="${s.label}">Servizio</h3>\n'
        '          <p th:text="${s.description}">Descrizione</p>\n'
        "        </div></div>",
    ),
    "preventivo": (
        '<div class="choice-grid">\n              <label class="check-card"><input type="checkbox" name="servizi_extra[]"',
        "\n          </div>\n        </fieldset>",
        'th:if="${quoteExtras == null}"',
        '<div class="choice-grid" th:if="${quoteExtras != null}" data-accessories>'
        '<label class="check-card" th:each="s : ${quoteExtras}"><input type="checkbox" name="servizi_extra[]" th:value="${s.label}">'
        '<span>[[${s.label}]]<small th:if="${s.hint != null}" th:text="${s.hint}">Suggerimento</small></span></label></div>',
    ),
}


def enumeration_blocks(html: str, key: str) -> str:
    """Adds the ENH-14 alternative after the ported block of a page (each block must exist exactly once)."""
    if key not in ENUMERATION_BLOCKS:
        return html
    start_text, after_text, condition, dynamic = ENUMERATION_BLOCKS[key]
    if html.count(start_text) != 1:
        raise SystemExit(f"{key}: ENH-14 block not found once")
    start = html.index(start_text)
    end = html.index(after_text, start)
    opening_end = html.index(">", start)
    return html[:opening_end] + " " + condition + html[opening_end:end] + dynamic + html[end:]


def targets():
    """(source file, template path, page key, error page?) for every page of the static site."""
    out = []
    for source in sorted(PUBLIC.rglob("*.html")):
        rel = source.relative_to(PUBLIC).as_posix()
        if rel == "404.html":
            out.append((source, TEMPLATES / "error" / "404.html", "404", True))
            continue
        key = "home" if rel == "index.html" else rel[: -len("/index.html")]
        out.append((source, TEMPLATES / "site" / f"{key}.html", key, False))
    return out


def main(check: bool) -> int:
    if not PUBLIC.is_dir():
        raise SystemExit(f"legacy checkout missing: {PUBLIC}")
    drift = []
    for source, target, key, error_page in targets():
        html = source.read_text(encoding="utf-8")
        # The header's nav key is the page's data-page (blog posts share "blog", chapter 06 §3.1).
        nav_key = re.search(r'<body[^>]*\sdata-page="([^"]*)"', html).group(1)
        ported = enumeration_blocks(port(html, nav_key, error_page), key)
        if check:
            if not target.is_file() or target.read_text(encoding="utf-8") != ported:
                drift.append(str(target.relative_to(ROOT)))
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(ported, encoding="utf-8")
            print(f"{source.relative_to(ROOT)} -> {target.relative_to(ROOT)}")
    if drift:
        print("templates differ from a fresh port:\n  " + "\n  ".join(drift))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main("--check" in sys.argv[1:]))
