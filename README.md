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
  "currency": "USD",
  "items": [
    { "productId": "product-456", "quantity": 2, "unitPrice": 19.99 }
  ]
}
```

The service validates request fields and persists each order and its items in one transaction. Order endpoints require an OIDC bearer token. The service uses the verified token subject (`sub`) as the customer ID; callers cannot select another customer in the request body. Reads, lists, and cancellations are scoped to that subject, and an order owned by another subject is reported as not found. API documentation is available at `/q/swagger-ui`; health checks are under `/q/health`, and Prometheus metrics are at `/q/metrics`.

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

Runtime API requests in dev mode also require a token from a trusted OIDC issuer; set `OIDC_AUTH_SERVER_URL` to that issuer before starting Quarkus. The test profile disables OIDC discovery and uses test identities, so the test suite does not require an identity provider.

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

### Local Keycloak identity provider

Keycloak is the identity provider for this local setup: it handles sign-in and token issuance. It runs in development mode with an ephemeral database, stays internal to Kubernetes, and is not suitable for production. The admin password is kept in a local Kubernetes Secret and is not stored in this repository.

Create a strong local admin password, deploy only the Keycloak manifest, and wait for it to become ready:

```powershell
$env:KEYCLOAK_ADMIN_PASSWORD = Read-Host "Local Keycloak admin password"
kubectl create secret generic keycloak-admin-credentials --namespace order-service `
  --from-literal=password=$env:KEYCLOAK_ADMIN_PASSWORD
Remove-Item Env:KEYCLOAK_ADMIN_PASSWORD
kubectl apply -f k8s/keycloak.yaml --namespace order-service
kubectl rollout status deployment/keycloak --namespace order-service
kubectl port-forward service/keycloak 8180:8080 --namespace order-service
```

Keep the port-forward open and visit `http://localhost:8180`. Sign in to the Administration Console with username `admin` and the password supplied above. Retrieve the password later from the local Kubernetes Secret if needed:

```powershell
$encodedPassword = kubectl get secret keycloak-admin-credentials --namespace order-service -o jsonpath='{.data.password}'
[System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encodedPassword))
```

The Keycloak admin console is available only through port-forwarding; the Kubernetes Service is a cluster-internal `ClusterIP`. Keycloak data is ephemeral and will be lost if its pod is recreated. Do not use this dev-mode deployment or its credentials for production.

After creating the `orders` realm in Keycloak, configure the issuer URL exactly as `http://keycloak:8080/realms/orders`. The API pod can reach the cluster-internal Keycloak Service at this address. This public issuer URL belongs in a ConfigMap, not in a Secret:

```powershell
$env:OIDC_AUTH_SERVER_URL = Read-Host "OIDC issuer base URL (for example https://identity.example.com/realms/orders)"
kubectl create configmap order-service-oidc --namespace order-service `
  --from-literal=auth-server-url=$env:OIDC_AUTH_SERVER_URL `
  --dry-run=client -o yaml | kubectl apply -f -
Remove-Item Env:OIDC_AUTH_SERVER_URL
```

The identity provider must issue signed JWT access tokens with the configured audience (`order-service`) and a stable, non-empty `sub` claim no longer than 128 characters. Configure that audience in the provider's API/resource settings. The service validates tokens using the provider's OIDC discovery/JWKS endpoints. Ensure the Kubernetes cluster can reach the issuer URL.

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

The service is only exposed as a cluster-internal `ClusterIP`; port-forwarding is the local access path. Kubernetes readiness and liveness endpoints remain available to probes, and Prometheus can scrape the cluster-internal metrics endpoint. Prices are accepted from the caller until a trusted catalog/pricing integration is added.

To call the API, provide an access token from the configured OIDC provider:

```powershell
$env:ACCESS_TOKEN = Read-Host "OIDC access token"
curl.exe -H "Authorization: Bearer $env:ACCESS_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"currency":"USD","items":[{"productId":"product-456","quantity":2,"unitPrice":19.99}]}' `
  http://localhost:8080/api/v1/orders
Remove-Item Env:ACCESS_TOKEN
```

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
| `OIDC_AUTH_SERVER_URL` | OIDC issuer/discovery URL | Required in production |
| `OIDC_AUDIENCE` | Required access-token audience | `order-service` |
| `QUARKUS_OTEL_TRACES_EXPORTER` | Trace exporter | `none` locally unless configured; `otlp` in Kubernetes |
| `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry Collector endpoint | `http://localhost:4317`; Kubernetes uses the `observability` service |
| `QUARKUS_OTEL_TRACES_SAMPLER_ARG` | Trace sampling ratio | `0.1`; local Kubernetes sets `1.0` |

Kubernetes loads the OIDC issuer URL from the `order-service-oidc` ConfigMap. Never disable token validation or use a permissive audience in production. Continue to restrict external exposure and use TLS at the ingress/load balancer.

## Next increments

The broader platform roadmap includes Customer, Product, Inventory, Cart, Order, and Payment services. Keycloak remains the identity provider; Customer Service will own customer profiles and map external identities to immutable internal customer IDs. Product Service will own catalog data, Inventory Service will own stock availability, Cart Service will own cart state, Order Service will own order lifecycle, and Payment Service will integrate with payment gateways. These are future phases; this repository currently implements the Order Service and local Keycloak foundation. Kafka with a transactional outbox is a future option for reliable cross-service events, while Redis cache-aside should be introduced when measured traffic justifies it.
