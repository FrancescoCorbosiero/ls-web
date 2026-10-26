package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The page of a saved quote's link (ENH-20, ADR 0037), public to whoever holds the token: the
 * quote, its state (still valid, ordered, withdrawn, expired), the PDF and «Ordina» — the order
 * form of the customer area, which asks to sign in first. Never indexed nor cached; a 404 for an
 * unknown token or while {@code quotes.saved} is off.
 */
@Controller
public class QuotePageController {

    static final String NOINDEX = "noindex, nofollow";

    private final QuoteService quotes;
    private final QuotePdf pdf;
    private final CustomerService customers;
    private final FeatureFlags flags;

    public QuotePageController(QuoteService quotes, QuotePdf pdf, CustomerService customers, FeatureFlags flags) {
        this.quotes = quotes;
        this.pdf = pdf;
        this.customers = customers;
        this.flags = flags;
    }

    @GetMapping(QuoteRoutes.PAGE)
    public String page(@PathVariable("token") String token, Model model, HttpServletResponse response) {
        QuoteView quote = quote(token);
        response.setHeader("X-Robots-Tag", NOINDEX);
        model.addAttribute("quote", quote);
        model.addAttribute("customer", customer(quote));
        model.addAttribute("open", quote.open(quotes.today()));
        model.addAttribute("closed", quote.open(quotes.today()) ? null : quotes.closedMessage(quote));
        model.addAttribute("pdfUrl", QuoteRoutes.page(token) + "/pdf");
        model.addAttribute("orderUrl", QuoteRoutes.accept(quote.id()));
        return "quotes/quote";
    }

    @GetMapping(QuoteRoutes.PDF)
    public ResponseEntity<byte[]> pdf(@PathVariable("token") String token) {
        QuoteView quote = quote(token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename("preventivo-" + quote.code() + ".pdf")
                                .build()
                                .toString())
                .header("X-Robots-Tag", NOINDEX)
                .body(pdf.render(quote, customer(quote)));
    }

    private QuoteView quote(String token) {
        if (!flags.isEnabled(FlagKey.QUOTES_SAVED)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        return quotes.byToken(token).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    private CustomerView customer(QuoteView quote) {
        return customers.find(quote.customerId()).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }
}
