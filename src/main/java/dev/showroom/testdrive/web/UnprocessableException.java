package dev.showroom.testdrive.web;

import org.springframework.http.HttpStatus;

/** 422: well-formed but breaks a business rule (slot outside opening hours, key reused for another body). */
public class UnprocessableException extends ApiException {
    public UnprocessableException(String code, String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }
}
