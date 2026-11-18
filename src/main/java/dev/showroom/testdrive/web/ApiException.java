package dev.showroom.testdrive.web;

import org.springframework.http.HttpStatus;

/** A business error with a stable machine-readable code; rendered as RFC 9457 problem details. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
