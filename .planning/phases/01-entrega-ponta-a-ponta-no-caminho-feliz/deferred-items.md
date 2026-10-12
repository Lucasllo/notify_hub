## Deferred Items

- `MustacheAutoConfiguration` do Boot é ativado no worker só porque o `jmustache` (plano 01-03) está no classpath
  status: open
  **What:** ao subir, o worker loga `WARN ... MustacheAutoConfiguration : Cannot find template location: classpath:/templates/` e registra a infraestrutura de view Mustache do MVC, que o worker não usa (o `TemplateRenderer` compila strings vindas do Mongo).
  **Found during:** smoke de boot do plano 01-05 contra o compose
  **Fix sugerido:** `spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.mustache.MustacheAutoConfiguration` no `application.yml` do worker (ou `spring.mustache.check-template-location: false`, que só cala o aviso). Fora do escopo do 01-05 porque vem do 01-03 e não afeta a entrega.
