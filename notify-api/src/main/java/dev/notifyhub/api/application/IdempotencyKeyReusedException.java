package dev.notifyhub.api.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** Esqueleto do RED: ainda sem Problem Details e sem uso no serviço. */
public class IdempotencyKeyReusedException extends ErrorResponseException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
