package dev.notifyhub.api.web.validation;

import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import dev.notifyhub.common.domain.RecipientRules;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/**
 * Regras do {@code POST /notifications} que as anotações não expressam: formato do destinatário por
 * canal ({@link RecipientRules}) e limites de {@code variables}. Registrado no {@code @InitBinder} do
 * controller, os erros caem no mesmo {@code MethodArgumentNotValidException} das constraints.
 *
 * <p>A regex de chave bloqueia {@code .}, {@code $} e {@code _} inicial: evita {@code MappingException}
 * do Mongo, injeção de operador e reserva {@code _notificationId} para o renderer (T-01-15).
 */
public class CreateNotificationRequestValidator implements Validator {

    static final int MAX_VARIABLES = 50;
    static final int MAX_STRING_LENGTH = 1000;
    private static final int MAX_ECHOED_KEY_LENGTH = 64;
    private static final Pattern KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");

    @Override
    public boolean supports(Class<?> clazz) {
        return CreateNotificationRequest.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        CreateNotificationRequest request = (CreateNotificationRequest) target;
        validateRecipient(request, errors);
        validateVariables(request.variables(), errors);
    }

    private static void validateRecipient(CreateNotificationRequest request, Errors errors) {
        // nulos/vazios já são cobertos pelas constraints do record
        if (request.channel() == null || request.recipient() == null || request.recipient().isBlank()) {
            return;
        }
        if (!RecipientRules.isValid(request.channel(), request.recipient())) {
            errors.rejectValue("recipient", "recipient.invalid",
                    "inválido para o canal " + request.channel() + ": use " + RecipientRules.describe(request.channel()));
        }
    }

    private static void validateVariables(Map<String, Object> variables, Errors errors) {
        if (variables == null) {
            return;
        }
        if (variables.size() > MAX_VARIABLES) {
            // não detalha chave a chave: limita o tamanho da resposta a um payload abusivo
            errors.rejectValue("variables", "variables.tooMany",
                    "aceita no máximo " + MAX_VARIABLES + " variáveis (recebidas " + variables.size() + ")");
            return;
        }
        variables.forEach((key, value) -> {
            if (key == null || !KEY.matcher(key).matches()) {
                errors.rejectValue("variables", "variables.key",
                        "chave '" + echo(key) + "' inválida: use letras, dígitos e _, começando por letra,"
                                + " com até 64 caracteres");
            } else if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                errors.rejectValue("variables", "variables.value",
                        "valor de '" + key + "' deve ser texto, número ou booleano");
            } else if (value instanceof String text && text.length() > MAX_STRING_LENGTH) {
                errors.rejectValue("variables", "variables.length",
                        "valor de '" + key + "' deve ter no máximo " + MAX_STRING_LENGTH + " caracteres");
            }
        });
    }

    private static String echo(String key) {
        if (key == null) {
            return "null";
        }
        return key.length() <= MAX_ECHOED_KEY_LENGTH ? key : key.substring(0, MAX_ECHOED_KEY_LENGTH) + "…";
    }
}
