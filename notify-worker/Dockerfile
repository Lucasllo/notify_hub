# Imagem do notify-worker. Build multi-stage: o Maven roda dentro do container,
# então quem clona o projeto não precisa de JDK nem de Maven no host (DEM-01).
# Contexto de build: a raiz do repositório (ver docker-compose.yml e .dockerignore).

# --- Estágio de build ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Os quatro POMs de módulo, cada um na sua pasta. O reator lê todos os módulos
# listados no POM pai mesmo com -pl, e sem o notify-it/pom.xml o Maven aborta.
COPY pom.xml .
COPY notify-common/pom.xml notify-common/
COPY notify-api/pom.xml notify-api/
COPY notify-worker/pom.xml notify-worker/
COPY notify-it/pom.xml notify-it/

# Só o código que o worker compila: notify-common (dependência) e o próprio worker.
COPY notify-common/src notify-common/src
COPY notify-worker/src notify-worker/src

# Cache BuildKit do repositório local do Maven: o segundo build não baixa tudo de novo.
# sharing=locked serializa os builds da API e do worker, que dividem o mesmo cache.
RUN --mount=type=cache,id=notify-m2,target=/root/.m2,sharing=locked \
    mvn -B -q -pl notify-worker -am -DskipTests package

# --- Estágio de runtime -------------------------------------------------------
FROM eclipse-temurin:21-jre

# Usuário de sistema sem privilégios: o processo não roda como root.
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

# Só o jar executável (classifier exec, Fase 3). O jar sem classifier não sobe sozinho.
COPY --from=build /src/notify-worker/target/notify-worker-*-exec.jar /app/app.jar

USER app
EXPOSE 8081

# Forma exec: o java é o PID 1 e recebe o SIGTERM do "docker compose stop",
# o que deixa o JFR do benchmark gravar a saída no encerramento.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
