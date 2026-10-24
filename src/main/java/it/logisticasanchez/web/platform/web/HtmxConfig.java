package it.logisticasanchez.web.platform.web;

/**
 * The {@code htmx-config} meta content of the customer area and back office (chapter 06 §8.5,
 * ADR 0018): no injected indicator styles, no eval, no script tags from responses, requests to
 * the own origin only. Rendered by {@code templates/fragments/htmx.html}; the value lives here so
 * a test can assert it and the layout cannot drift.
 */
public final class HtmxConfig {

    public static final String META_CONTENT =
            "{\"includeIndicatorStyles\":false,\"allowEval\":false,\"allowScriptTags\":false,\"selfRequestsOnly\":true}";

    private HtmxConfig() {}

    /** The {@code hx-headers} attribute value sending the CSRF token with every HTMX request. */
    public static String hxHeaders(String headerName, String token) {
        return "{\"" + headerName + "\": \"" + token + "\"}";
    }
}
