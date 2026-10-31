package dev.notifyhub.api.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Metadados do OpenAPI da API (API-08), servidos pelo springdoc em {@code /v3/api-docs} e no Swagger
 * UI ({@code /swagger-ui/index.html}).
 *
 * <p>Declara o esquema de segurança {@value #ADMIN_API_KEY_SCHEME} (header {@code X-API-Key}), que
 * só o {@code AdminController} exige via {@code @SecurityRequirement}. É só documentação: quem protege
 * {@code /admin/**} de fato é o interceptor do {@code AdminWebConfiguration}. A documentação em si
 * fica aberta, sem chave.
 *
 * <p>Não é um {@code WebMvcConfigurer}, então as fatias {@code @WebMvcTest} não a carregam.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "Notify Hub API",
        version = "v1",
        description = """
                Serviço de notificações multicanal (e-mail, SMS e webhook, simulados) sobre RabbitMQ e \
                MongoDB. Cria notificações (POST /notifications), que são persistidas e publicadas para o \
                worker, e as consulta por id, em lista paginada ou em estatísticas. O header opcional \
                Idempotency-Key torna a criação idempotente: repetir o mesmo pedido com a mesma chave \
                devolve a notificação já criada. As operações de administração (reprocessar a DLQ de um \
                canal e republicar as notificações PUBLISH_FAILED) exigem o header X-API-Key."""))
@SecurityScheme(
        name = OpenApiConfiguration.ADMIN_API_KEY_SCHEME,
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-API-Key",
        description = "Chave de administração (notify.admin.api-key / NOTIFY_ADMIN_API_KEY)")
public class OpenApiConfiguration {

    /** Nome do esquema de segurança da chave de administração no OpenAPI. */
    public static final String ADMIN_API_KEY_SCHEME = "adminApiKey";
}
