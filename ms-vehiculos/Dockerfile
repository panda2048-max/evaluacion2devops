# syntax=docker/dockerfile:1

# ---------- Etapa 1: build ----------
# Imagen con JDK completo + Maven wrapper, solo para compilar. No llega a la imagen final.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Primero solo los archivos de Maven: esta capa se cachea mientras no cambie el pom.xml
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Solo se empaqueta: las pruebas unitarias se ejecutan aparte con ./mvnw verify
RUN ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---------- Etapa 2: runtime ----------
# Solo JRE (sin compilador ni Maven) sobre Alpine: imagen mas pequena y menor superficie de ataque
FROM eclipse-temurin:21-jre-alpine

ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
# Metadatos OCI: permiten rastrear la imagen hasta el commit que la genero
LABEL org.opencontainers.image.title="ms-vehiculos" \
      org.opencontainers.image.description="Microservicio CRUD de vehiculos (Spring Boot)" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

# Usuario sin privilegios: el proceso nunca corre como root
RUN addgroup -S -g 1001 spring && adduser -S -u 1001 -G spring spring \
    && apk add --no-cache curl

WORKDIR /app
# Capas de Spring Boot ordenadas de menos a mas cambiantes (mejor uso de cache)
COPY --from=build --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/application/ ./

USER 1001:1001

ENV SERVER_PORT=9004 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 9004

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fs http://localhost:${SERVER_PORT}/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
