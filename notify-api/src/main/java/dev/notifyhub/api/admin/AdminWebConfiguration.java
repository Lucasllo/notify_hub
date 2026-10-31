package dev.notifyhub.api.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuração web dos endpoints administrativos. Registra as {@link AdminProperties} aqui, e não no
 * {@code ApiConfiguration}, porque as fatias {@code @WebMvcTest} carregam os {@code WebMvcConfigurer}
 * e precisam do bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties.class)
public class AdminWebConfiguration implements WebMvcConfigurer {
}
