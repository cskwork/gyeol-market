package io.github.cskwork.lunepick.shared;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** RFC 9457 detail stays internal; the HTTP adapter preserves the storefront envelope. */
public class ApiException extends RuntimeException {
    private final String code;
    private final ProblemDetail problem;

    public ApiException(int status, String code, String message) {
        super(message);
        this.code = code;
        this.problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), message);
        this.problem.setTitle(code);
    }

    public int status() {
        return problem.getStatus();
    }

    public String code() {
        return code;
    }

    public ProblemDetail problem() {
        return problem;
    }
}
