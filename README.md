# evaluacion2devops — Pipeline CI/CD para `ms-vehiculos`

[![CI/CD ms-vehiculos](https://github.com/panda2048-max/evaluacion2devops/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/panda2048-max/evaluacion2devops/actions/workflows/ci-cd.yml)

Pipeline CI/CD en **GitHub Actions** que automatiza el ciclo de vida completo del microservicio
`ms-vehiculos` (Spring Boot 4 · Java 21 · MySQL): pruebas automatizadas, análisis de seguridad,
contenerización, publicación de la imagen y despliegue automático en dos entornos simulados
(Docker Compose y Kubernetes).

**Integrantes:** _<nombre 1>_ · _<nombre 2>_

---

## 1. Estructura del repositorio

```
.github/workflows/ci-cd.yml    Pipeline CI/CD
ms-vehiculos/
├── Dockerfile                 Imagen multi-stage, usuario no-root, healthcheck
├── .dockerignore
├── docker-compose.yml         Entorno "staging": app + MySQL
├── .env.example               Plantilla de variables (el .env real no se versiona)
├── k8s/                       Entorno "producción": manifiestos Kubernetes (kustomize)
│   ├── namespace.yaml         Namespace con Pod Security "restricted"
│   ├── configmap.yaml         Configuración no sensible
│   ├── mysql.yaml             StatefulSet + volumen persistente + Service headless
│   ├── deployment.yaml        2 réplicas, rolling update, probes, límites, securityContext
│   ├── service.yaml           Service ClusterIP
│   ├── hpa.yaml               Autoescalado 2–5 pods por CPU/memoria
│   ├── pdb.yaml               PodDisruptionBudget (mínimo 1 pod disponible)
│   └── networkpolicy.yaml     MySQL solo accesible desde la app
├── pom.xml                    Build Maven + JaCoCo con umbral de cobertura
└── src/                       Código y pruebas
```

## 2. Flujo del pipeline

```mermaid
flowchart LR
    A[push / PR a main] --> B[1. Tests + cobertura<br/>JUnit · Mockito · MockMvc · JaCoCo ≥80%]
    B --> C[2. Seguridad<br/>Snyk Open Source + Snyk Code]
    C --> D[3. Imagen Docker<br/>build + Snyk Container]
    D -->|solo main| E[Push a GHCR<br/>tag sha-xxxxxxx]
    E --> F[4. Deploy staging<br/>Docker Compose + smoke tests]
    F --> G[5. Deploy producción<br/>Kubernetes kind + smoke tests]
    B -. falla .-> X[Alerta: issue automático]
    C -. falla .-> X
    D -. falla .-> X
    F -. falla .-> X
    G -. falla .-> X
```

Cada etapa depende de la anterior (`needs`): **si una etapa falla, las siguientes no se ejecutan**,
por lo que código sin pruebas, con vulnerabilidades o que no arranca nunca llega a desplegarse.

| # | Job | Qué hace | Bloquea si… |
|---|-----|----------|-------------|
| 1 | Tests y cobertura | `./mvnw verify`: compila, ejecuta 18 pruebas (servicio + controller), genera reporte JaCoCo | falla alguna prueba o la cobertura de líneas es < 80 % |
| 2 | Seguridad (Snyk) | `snyk test` (dependencias), `snyk code test` (código fuente), sube SARIF a *Security*, `snyk monitor` | hay vulnerabilidades **high** o **critical** |
| 3 | Imagen Docker | Construye la imagen, la escanea con `snyk container test` y la publica en GHCR | la imagen tiene vulnerabilidades **critical** |
| 4 | Deploy staging | `docker compose up --wait` con la imagen recién publicada + pruebas de humo (health, POST, GET, 404) | algún contenedor no queda *healthy* o falla una prueba de humo |
| 5 | Deploy producción | Crea un cluster Kubernetes (kind), aplica los manifiestos, espera el rollout + pruebas de humo | el rollout no termina o fallan las pruebas de humo |
| — | Alerta de fallo | Abre un *issue* con las etapas fallidas, el commit y el enlace a la ejecución | — |

En los **Pull Requests** se ejecutan las etapas 1–3 (sin publicar ni desplegar), de modo que se
valida el cambio antes de mezclarlo en `main`.

## 3. Contenedores (Dockerfile)

- **Multi-stage**: la etapa `build` usa `eclipse-temurin:21-jdk-alpine` para compilar; la imagen
  final usa solo `eclipse-temurin:21-jre-alpine` (sin JDK ni Maven), lo que reduce tamaño y superficie de ataque.
- **Capas de Spring Boot** (`jarmode=tools extract --layers`): las dependencias quedan en una capa
  separada del código, así un cambio en el código no vuelve a descargar todas las dependencias.
- **Usuario no-root** (UID 1001) y `HEALTHCHECK` contra `/actuator/health`.
- **Memoria consciente del contenedor**: `-XX:MaxRAMPercentage=75` ajusta el heap al límite del contenedor.
- **Etiquetas OCI** (`org.opencontainers.image.revision`, `version`, `created`, `source`) que
  vinculan la imagen con el commit que la generó.

## 4. Orquestación

### Docker Compose (staging)
- Servicios `app` y `db` en una red interna; MySQL **no** se expone al host.
- `depends_on: condition: service_healthy`: la app solo arranca cuando MySQL responde.
- Límites de CPU/memoria, `restart: unless-stopped`, sistema de archivos de solo lectura y `no-new-privileges`.
- Credenciales por `.env` (no versionado); en el pipeline se generan aleatoriamente en cada ejecución.

### Kubernetes (producción simulada)
| Aspecto | Configuración |
|---|---|
| Disponibilidad | 2 réplicas, `RollingUpdate` con `maxUnavailable: 0` (sin downtime), PDB `minAvailable: 1` |
| Escalabilidad | HPA de 2 a 5 réplicas al 70 % de CPU / 80 % de memoria, con ventana anti-*flapping* de 5 min |
| Salud | `startupProbe`, `livenessProbe` y `readinessProbe` sobre los endpoints de Actuator; initContainer que espera a MySQL |
| Recursos | `requests` y `limits` de CPU/memoria en todos los contenedores |
| Seguridad | Pod Security `restricted`, `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities: drop ALL`, seccomp, sin token de ServiceAccount, NetworkPolicy, credenciales en `Secret` |
| Persistencia | MySQL como `StatefulSet` con `PersistentVolumeClaim` |
| Rollback | `revisionHistoryLimit: 5` + anotación `change-cause` → `kubectl rollout undo` |

## 5. Trazabilidad: del commit a producción

| Pregunta | Dónde se responde |
|---|---|
| ¿Qué commit está desplegado? | Tag de la imagen `sha-<commit>` y etiqueta OCI `org.opencontainers.image.revision` |
| ¿Qué imagen exacta se desplegó? | El **digest** inmutable se registra en el resumen de la ejecución (*Job summary*) |
| ¿Qué ejecución del pipeline hizo el despliegue? | Anotación `kubernetes.io/change-cause` del Deployment (commit + URL de la ejecución); `kubectl rollout history` |
| ¿Cuándo y en qué entorno? | Pestaña **Deployments / Environments** de GitHub (`staging`, `production`) |
| ¿Pasó las pruebas y el análisis? | Artefacto `reportes-calidad-<sha>` (Surefire + JaCoCo, 30 días), resumen de cobertura, resultados de Snyk en **Security → Code scanning** |
| ¿Qué falló y quién lo provocó? | Issue automático con etiqueta `pipeline-failure`, commit, autor y enlace al log |

## 6. Calidad y seguridad

**Políticas de calidad**
- Toda la lógica de negocio (`VehiculoService`) y la API REST (`VehiculoController`) tienen pruebas:
  casos felices, 404 (no existe), 409 (patente duplicada) y 400 (validación de entrada).
- Cobertura mínima de líneas: **80 %** (regla `jacoco:check`); si baja, el build falla.
- Pruebas de humo contra la aplicación **desplegada** en ambos entornos.

**Políticas de seguridad**
- Dependencias y código analizados con Snyk; umbral `high` → bloqueo del pipeline.
- Imagen escaneada con Snyk Container; umbral `critical` → bloqueo.
- `snyk monitor` registra cada versión de `main` para recibir **alertas por correo** ante nuevas vulnerabilidades.
- Sin credenciales en el repositorio: se eliminaron la IP y la contraseña que estaban por defecto en
  `application.yml`; se usan `.env`, `Secret` de Kubernetes y *GitHub Secrets*.
- Actuator expone solo `health` e `info`.
- Permisos mínimos del `GITHUB_TOKEN` por job.

## 7. Configuración necesaria en GitHub

1. **Secret `SNYK_TOKEN`**: en <https://app.snyk.io> → *Account settings* → *Auth Token*.
   Copiarlo en *Settings → Secrets and variables → Actions → New repository secret*.
2. **Habilitar Snyk Code** en Snyk: *Settings → Snyk Code → Enable*.
3. (Opcional) **Aprobación manual para producción**: *Settings → Environments → production →
   Required reviewers*. Así el despliegue a producción espera la aprobación de un integrante.
4. (Opcional) **Proteger `main`**: *Settings → Branches → Add rule* exigiendo que pasen los checks
   `1. Tests y cobertura`, `2. Seguridad (Snyk)` y `3. Imagen Docker` antes de mezclar un PR.

## 8. Ejecución local

```bash
cd ms-vehiculos

# Pruebas + cobertura (reporte en target/site/jacoco/index.html)
./mvnw verify

# Entorno completo con Docker Compose
cp .env.example .env          # editar las claves
docker compose up -d --build --wait
curl http://localhost:9004/actuator/health
# Swagger UI: http://localhost:9004/swagger-ui.html
docker compose down -v

# Kubernetes local (kind / minikube / Docker Desktop)
docker build -t ms-vehiculos:latest .
kind load docker-image ms-vehiculos:latest      # solo si usas kind
kubectl apply -f k8s/namespace.yaml
kubectl -n vehiculos create secret generic vehiculos-db \
  --from-literal=DB_USERNAME=vehiculos \
  --from-literal=DB_PASSWORD=<clave> \
  --from-literal=DB_ROOT_PASSWORD=<clave-root>
kubectl apply -k k8s/
kubectl -n vehiculos port-forward svc/ms-vehiculos 8080:80
```

## 9. Justificación de decisiones técnicas

> ✍️ **Sección a redactar por el equipo, sin IA** (requisito de la evaluación).
> Sugerencias: por qué Snyk y esos umbrales de severidad, por qué 80 % de cobertura,
> por qué Compose para staging y Kubernetes para producción, por qué esos valores de HPA y límites.

## 10. Uso de Inteligencia Artificial

> ✍️ Completar y revisar por el equipo. Ejemplo de declaración:

| Herramienta | Uso | Validación realizada por el equipo |
|---|---|---|
| Claude Code (Anthropic, modelo Claude Opus 5.5) | Apoyo en la generación inicial de Dockerfile, docker-compose, manifiestos Kubernetes, workflow de GitHub Actions, pruebas del controller y estructura de este README | _describir qué revisaron, probaron o modificaron_ |

Cita según <https://bibliotecas.duoc.cl/ia>: _completar con el formato de la biblioteca_.

## 11. Conclusiones y reflexiones individuales

> ✍️ **Obligatorio, redactado por cada integrante sin apoyo de IA.**

### Reflexión de _<integrante 1>_
_…aprendizaje y contribución al proyecto…_

### Reflexión de _<integrante 2>_
_…aprendizaje y contribución al proyecto…_
