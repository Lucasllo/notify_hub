package dev.notifyhub.api.web;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import dev.notifyhub.common.domain.Channel;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Problem Details (RFC 9457) para toda a API. Com este bean o handler padrão do Boot sai de cena e
 * toda {@code ErrorResponseException} vira {@code application/problem+json} com os headers da
 * exceção (ex.: {@code Retry-After}). Nenhuma mensagem interna de exceção nem stack trace vai no corpo.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String ACCEPTED_CHANNELS = Arrays.stream(Channel.values())
            .map(Channel::name)
            .collect(Collectors.joining(", ", "valores aceitos: ", ""));

    /** Um erro de validação; {@code field} é nulo para erros que não pertencem a um campo. */
    public record FieldProblem(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldProblem(
                        error instanceof FieldError fieldError ? fieldError.getField() : null,
                        error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        FieldProblem error = invalidChannel(ex)
                ? new FieldProblem("channel", ACCEPTED_CHANNELS)
                : new FieldProblem(null, "corpo JSON inválido");
        return handleExceptionInternal(ex, validationProblem(List.of(error)), headers, HttpStatus.BAD_REQUEST,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && problem.getInstance() == null
                && request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        return response;
    }

    private static ProblemDetail validationProblem(List<FieldProblem> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(ProblemTypes.VALIDATION);
        problem.setTitle("Requisição inválida");
        problem.setDetail("Um ou mais campos são inválidos.");
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Valor fora do enum {@link Channel} (ex.: {@code "PUSH"}) em vez de JSON malformado. */
    private static boolean invalidChannel(HttpMessageNotReadableException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidFormatException invalid && invalid.getTargetType() == Channel.class) {
                return true;
            }
        }
        return false;
    }
}
