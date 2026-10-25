package it.logisticasanchez.web.platform.web.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The {@code /error} endpoint of the container's error dispatch (errors raised outside Spring
 * MVC: in the servlet container or in a filter that calls {@code sendError}). It answers with
 * the error model instead of the Boot whitelabel page (ADR 0002); a direct call without an
 * error attribute is a plain 404.
 */
@Controller
public class PlatformErrorController implements ErrorController {

    private final ErrorResponder responder;

    public PlatformErrorController(ErrorResponder responder) {
        this.responder = responder;
    }

    @RequestMapping("${server.error.path:/error}")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        ErrorCode code = status instanceof Integer s ? ErrorCode.forStatus(s) : ErrorCode.NOT_FOUND;
        responder.respond(request, response, code, null);
    }
}
