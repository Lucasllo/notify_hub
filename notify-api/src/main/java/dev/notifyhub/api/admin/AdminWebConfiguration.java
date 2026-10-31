package dev.notifyhub.api.admin;

import dev.notifyhub.api.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuração web dos endpoints administrativos (ADM-04). Registra as {@link AdminProperties} aqui,
 * e não no {@code ApiConfiguration}, porque as fatias {@code @WebMvcTest} carregam os
 * {@code WebMvcConfigurer} e precisam do bean.
 *
 * <p>O {@link ApiKeyInterceptor} cobre só {@code /admin/**} e roda antes do binding dos argumentos do
 * controller: um chamador sem a chave recebe 401 mesmo com canal ou {@code max} inválidos e nunca
 * chega a ler a DLQ. A {@code ErrorResponseException} lançada em {@code preHandle} passa pelos
 * resolvers do {@code DispatcherServlet} e vira Problem Details no {@code ApiExceptionHandler}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties.class)
public class AdminWebConfiguration implements WebMvcConfigurer {

    private final AdminProperties props;

    public AdminWebConfiguration(AdminProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiKeyInterceptor(props.apiKey())).addPathPatterns("/admin/**");
    }

    /**
     * Exige o header {@code X-API-Key} com a chave configurada.
     *
     * <p>A chave recebida e a configurada são comparadas como SHA-256 com
     * {@link MessageDigest#isEqual}: dois digests de 32 bytes, num tempo que não depende do tamanho
     * nem do conteúdo da chave (T-03-04). Sem trim nem normalização de caixa. Sem chave configurada,
     * recusa toda chamada (fail closed, T-03-05). A chave nunca é logada nem ecoada (T-03-10).
     */
    static class ApiKeyInterceptor implements HandlerInterceptor {

        static final String HEADER = "X-API-Key";

        private static final Logger log = LoggerFactory.getLogger(ApiKeyInterceptor.class);

        /** SHA-256 da chave configurada; nulo quando não há chave (fail closed). */
        private final byte[] expectedDigest;

        ApiKeyInterceptor(String configuredKey) {
            if (configuredKey == null || configuredKey.isBlank()) {
                this.expectedDigest = null;
                log.warn("notify.admin.api-key não está configurada: /admin/** recusa todas as chamadas (fail closed)");
            } else {
                this.expectedDigest = sha256(configuredKey);
            }
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            String provided = request.getHeader(HEADER);
            if (expectedDigest == null || provided == null
                    || !MessageDigest.isEqual(expectedDigest, sha256(provided))) {
                throw unauthorized();
            }
            return true;
        }

        private static ErrorResponseException unauthorized() {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
            problem.setType(ProblemTypes.UNAUTHORIZED);
            problem.setTitle("Não autorizado");
            problem.setDetail("Envie o header X-API-Key com a chave de administração.");
            return new ErrorResponseException(HttpStatus.UNAUTHORIZED, problem, null);
        }

        private static byte[] sha256(String value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 indisponível na JVM", e);
            }
        }
    }
}
