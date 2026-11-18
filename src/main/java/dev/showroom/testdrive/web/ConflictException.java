package dev.showroom.testdrive.web;

import org.springframework.http.HttpStatus;

/** 409: the request was fine but clashes with the current state (slot taken, request still running). */
public class ConflictException extends ApiException {
    public ConflictException(String code, String detail) {
        super(HttpStatus.CONFLICT, code, detail);
    }
}
