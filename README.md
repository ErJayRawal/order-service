# Order Service

A Quarkus REST service for creating, viewing, listing, and cancelling orders. PostgreSQL stores orders and line items; Flyway applies schema migrations. The API accepts caller-provided unit prices in v1, and calculates line and order totals using decimal arithmetic.

## Application structure

Order code is organized by responsibility under `com.acme.cloud.orders`:

| Package | Responsibility |
|---|---|
| `api` | REST resource and HTTP contract |
| `dto` | Request, response, and API error DTOs |
| `service` | Transactional order use cases |
| `repository` | Persistence access and order queries |
| `entity` | JPA entities and order status |
| `mapper` | Request-to-entity and entity-to-response conversions |
| `exception` | Domain exceptions and HTTP exception mappers |

The resource delegates to the service, which coordinates repository operations and the mapper. The order entity owns its pending-to-cancelled state transition. This keeps HTTP, persistence, conversion, and use-case responsibilities separate without adding an unnecessary general-purpose pattern framework.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/orders` | Create an order; returns `201` and a `Location` header |
| `GET` | `/api/v1/orders/{id}` | Retrieve an order |
| `GET` | `/api/v1/orders?page=0&size=20` | List orders, newest first (`size` is limited to 100) |
| `POST` | `/api/v1/orders/{id}/cancel` | Cancel a pending order; repeated cancellation is safe |

Example request:

```json
{
  "customerId": "customer-123",
  "currency": "USD",
  "items": [
    { "productId": "product-456", "quantity": 2, "unitPrice": 19.99 }
  ]
}
```

The service validates request fields and persists each order and its items in one transaction. API documentation is available at `/q/swagger-ui`; health checks are under `/q/health`, and Prometheus metrics are at `/q/metrics`.

## Run and test locally

Requirements: JDK 21 and Docker Desktop with its engine running. Quarkus Dev Services starts an ephemeral PostgreSQL container for tests and dev mode.

```powershell
.\mvnw.cmd test
.\mvnw.cmd quarkus:dev
```

The development API listens on `http://localhost:8080`. To package a JVM application:

```powershell
.\mvnw.cmd package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t order-service:local .
```

## Deploy to Docker Desktop Kubernetes

Enable Kubernetes in Docker Desktop, confirm `kubectl config current-context` is `docker-desktop`, and ensure the Docker engine is running. This setup uses a single PostgreSQL StatefulSet with a local persistent volume claim; it is suitable for local development, not highly available production hosting.

Create a namespace and a local Kubernetes Secret. Set `ORDER_DB_PASSWORD` to a strong local password before running the second command; do not commit credentials:

```powershell
$env:ORDER_DB_PASSWORD = Read-Host "Database password"
kubectl create namespace order-service
kubectl create secret generic order-db-credentials --namespace order-service `
  --from-literal=username=order_service `
  --from-literal=password="$env:ORDER_DB_PASSWORD"
Remove-Item Env:ORDER_DB_PASSWORD
```

Set a separate local Grafana administrator password and create its Kubernetes Secret:

```powershell
$env:GRAFANA_ADMIN_PASSWORD = Read-Host "Grafana admin password"
kubectl create secret generic observability-credentials --namespace order-service `
  --from-literal=grafana-admin-password=$env:GRAFANA_ADMIN_PASSWORD
Remove-Item Env:GRAFANA_ADMIN_PASSWORD
```

Build the image into Docker Desktop's local image store and deploy the manifests:

```powershell
.\mvnw.cmd package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t order-service:local .
kubectl apply -k k8s
kubectl rollout status deployment/order-service --namespace order-service
kubectl port-forward service/order-service 8080:80 --namespace order-service
```

The API is then available at `http://localhost:8080`. Keep the port-forward terminal open. PostgreSQL data is stored in the `postgres-data` PVC; deleting that claim deletes local order data. Back up persistent data before replacing or removing the claim.

### Local observability

The local Kubernetes manifests also deploy Grafana's LGTM development image (OpenTelemetry Collector, Prometheus, Tempo, Loki, and Grafana) plus Grafana Alloy for application log collection. The bundle is intended for local development and testing, not as a highly available production observability platform. It requests 1 GiB and is limited to 3 GiB; allocate adequate memory to Docker Desktop. Telemetry data is kept in the `observability-data` PVC.

Open another terminal and forward Grafana's port:

```powershell
kubectl port-forward service/observability 3000:3000 --namespace order-service
```

Visit `http://localhost:3000` and sign in as `admin` using the password stored in `observability-credentials`. The Grafana service and OTLP receiver remain cluster-internal; use port-forwarding for local access. The order service exports sampled traces to the collector, Prometheus scrapes `/q/metrics`, and Alloy tails only order-service pod logs through namespace-scoped Kubernetes permissions. In Grafana Explore, select Loki and query `{service_name="order-service"}`; select Tempo for traces or Prometheus for metrics.

To retrieve the generated Grafana password locally when needed:

```powershell
$encodedPassword = kubectl get secret observability-credentials --namespace order-service -o jsonpath='{.data.grafana-admin-password}'
[System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encodedPassword))
```

Every HTTP response includes `X-Correlation-ID`. A valid caller-provided value containing 1-64 letters, digits, dots, underscores, or hyphens is propagated; missing or invalid values are replaced with a generated UUID. Application JSON logs include correlation and active trace/span identifiers in MDC when available. Correlation IDs identify a request across services, while trace IDs identify the distributed trace; they are separate identifiers.

The service is only exposed as a cluster-internal `ClusterIP`; port-forwarding is the local access path. **V1 has no authentication or authorization. Do not expose it to an untrusted network.** Prices are accepted from the caller until a trusted catalog/pricing integration is added.

### Deploy an image published by CI

For a public GitHub Container Registry package, update the deployment image to the published SHA tag:

```powershell
kubectl set image deployment/order-service order-service=ghcr.io/<owner>/<repository>:sha-<commit-sha> --namespace order-service
kubectl rollout status deployment/order-service --namespace order-service
```

Use the lowercase GitHub owner/repository and the full commit SHA shown on the Actions run. For a private GHCR package, configure an `imagePullSecret` on the Kubernetes service account before updating the image.

## Free CI and image publishing

`.github/workflows/ci-cd.yml` runs tests on pull requests and pushes to `develop`, `main`, and version tags. Successful pushes to `main` and `v*` tags also build the container image and publish branch, version, and SHA tags to GHCR using the repository-scoped `GITHUB_TOKEN`; no paid registry credentials are required. Docker Hub publishing is separate from this workflow.

GitHub Actions and GHCR have free usage for public repositories/packages. Private repositories and packages are subject to GitHub's included minutes, storage, and bandwidth quotas and may incur charges if those quotas are exceeded; check current GitHub plan limits before relying on them. CI does not deploy to Docker Desktop: GitHub-hosted runners cannot access a developer's local cluster. The documented `kubectl set image` command is the manual deployment step.

For local Docker Hub deployments, build and push your selected tag from a Docker Hub-authenticated machine, then update the Kubernetes Deployment image. Do not put Docker Hub credentials in the repository or image.

## Configuration

| Variable | Purpose | Default |
|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | PostgreSQL JDBC URL | Required for production; Dev Services in dev/test |
| `QUARKUS_DATASOURCE_USERNAME` | Database user | Required for production |
| `QUARKUS_DATASOURCE_PASSWORD` | Database password | Required for production |
| `QUARKUS_OTEL_TRACES_EXPORTER` | Trace exporter | `none` locally unless configured; `otlp` in Kubernetes |
| `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry Collector endpoint | `http://localhost:4317`; Kubernetes uses the `observability` service |
| `QUARKUS_OTEL_TRACES_SAMPLER_ARG` | Trace sampling ratio | `0.1`; local Kubernetes sets `1.0` |

The Kubernetes manifests set the connection URL from a ConfigMap and credentials from the Secret. Do not expose the unauthenticated API publicly.

## Next increments

Customer authentication/authorization, trusted catalog pricing and inventory, idempotency keys, and payment integration are intentionally not part of v1. Add these before exposing order creation to real customers. Kafka with a transactional outbox and Redis cache-aside remain future increments; PostgreSQL remains the source of truth.
