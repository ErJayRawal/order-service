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

Keycloak is the identity provider: it handles sign-in and token issuance. The local deployment runs Keycloak in production mode against a dedicated PostgreSQL StatefulSet, with persistent volumes for both Keycloak and its database. It imports the `orders` realm, a development-only public client, and a bearer-only `order-service` API resource client from version-controlled configuration. A protocol mapper adds the API resource client as the access-token audience. Admin and database credentials are local Kubernetes Secrets; do not commit them. The cluster remains single-node, so the volumes protect against pod replacement, not node or disk failure.

The local cluster already has `keycloak-admin-credentials` and `keycloak-db-credentials`; preserve these Secrets and do not overwrite or rotate them without changing the corresponding Keycloak/PostgreSQL credentials. For a fresh namespace, create them once before deployment. This generates a strong database password locally and passes it through a temporary file rather than putting it in the command line or repository:

```powershell
$adminPassword = Read-Host "Local Keycloak admin password"
$adminFile = [System.IO.Path]::GetTempFileName()
$dbFile = [System.IO.Path]::GetTempFileName()
try {
  kubectl create secret generic keycloak-admin-credentials --namespace order-service `
    --from-file=password=$adminFile

  $bytes = New-Object byte[] 32
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  [System.IO.File]::WriteAllText($dbFile, [Convert]::ToBase64String($bytes))
  kubectl create secret generic keycloak-db-credentials --namespace order-service `
    --from-literal=username=keycloak --from-file=password=$dbFile
}
finally {
  Remove-Item $adminFile, $dbFile -ErrorAction SilentlyContinue
  $adminPassword = $null
  $bytes = $null
}
```

Build both application images and run the test suite. Deploy Keycloak separately first so it is ready before the secured Order Service starts:

```powershell
.\mvnw.cmd verify
docker build -f src/main/docker/Dockerfile.jvm -t order-service:local .
docker build -f k8s/keycloak/Dockerfile -t keycloak-local:26.8.1 k8s/keycloak
kubectl apply -k k8s/keycloak
kubectl rollout status statefulset/keycloak-postgres --namespace order-service
kubectl rollout status deployment/keycloak --namespace order-service
kubectl port-forward service/keycloak 8180:8080 --namespace order-service
```

Keep the port-forward open and visit `http://localhost:8180`. Sign in to the Administration Console as `admin`. The realm import creates `orders`, the `order-service-local` development client, the `order-service` bearer-only API resource client, and an audience mapper that adds `order-service` to access-token audiences. Direct password grants are enabled on this local-only test client for command-line integration testing; use Authorization Code with PKCE for interactive clients, and do not enable password grants for production clients. Create test users in the `orders` realm; never use the Keycloak bootstrap admin as an application user. Keycloak's realm import does not reconcile an already-existing realm; make later changes to persisted realms through the Admin Console or Admin REST API, and keep `realm.json` aligned for fresh deployments. Verify the discovery document before deploying the API:

```powershell
$metadata = Invoke-RestMethod http://localhost:8180/realms/orders/.well-known/openid-configuration
$metadata.issuer
```

The issuer must be `http://localhost:8180/realms/orders`. The application uses the cluster-internal Keycloak service for discovery/JWKS requests while validating that external issuer value. After Keycloak is ready, apply the full stack and wait for the service rollout:

```powershell
kubectl apply -k k8s
kubectl rollout status deployment/order-service --namespace order-service
```

The Keycloak admin console is available only through port-forwarding; its Kubernetes Service is a cluster-internal `ClusterIP`. Realm state lives in PostgreSQL and Keycloak data has its own PVC. Back up both before deleting or replacing claims. This is a production-mode application configuration for local development, not a highly available production identity deployment; production also requires TLS, managed secrets, backups, monitoring, and multi-node capacity.

Do not delete the `keycloak-data` or `keycloak-postgres-data-keycloak-postgres-0` PVCs to restart Keycloak: both contain persistent identity data. This deployment is production-mode configuration intended for a single-node local cluster, but it does not provide production HA or node-failure protection.

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

### Build and deploy manually

This repository does not use GitHub Actions. Run tests and build the image locally, then deploy it to Docker Desktop Kubernetes:

```powershell
.\mvnw.cmd verify
.\mvnw.cmd package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t order-service:local .
kubectl apply -k k8s
kubectl rollout status deployment/order-service --namespace order-service
```

For publishing an image to Docker Hub, authenticate with Docker locally, tag the image for your Docker Hub repository, and push it manually. Never put registry credentials in the repository or image. Git commits and pushes to the GitHub repository remain a separate source-control workflow and do not trigger builds or deployments.

## Configuration

| Variable | Purpose | Default |
|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | PostgreSQL JDBC URL | Required for production; Dev Services in dev/test |
| `QUARKUS_DATASOURCE_USERNAME` | Database user | Required for production |
| `QUARKUS_DATASOURCE_PASSWORD` | Database password | Required for production |
| `OIDC_AUTH_SERVER_URL` | OIDC issuer/discovery URL | Required in production |
| `OIDC_TOKEN_ISSUER` | Expected issuer claim, distinct from the internal discovery URL when needed | Defaults to the internal Keycloak URL |
| `OIDC_JWKS_PATH` | JWKS endpoint relative to the OIDC realm URL | `/protocol/openid-connect/certs` |
| `OIDC_AUDIENCE` | Required access-token audience | `order-service` |
| `QUARKUS_OTEL_TRACES_EXPORTER` | Trace exporter | `none` locally unless configured; `otlp` in Kubernetes |
| `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` | OpenTelemetry Collector endpoint | `http://localhost:4317`; Kubernetes uses the `observability` service |
| `QUARKUS_OTEL_TRACES_SAMPLER_ARG` | Trace sampling ratio | `0.1`; local Kubernetes sets `1.0` |

Kubernetes loads the OIDC issuer URL from the `order-service-oidc` ConfigMap. Never disable token validation or use a permissive audience in production. Continue to restrict external exposure and use TLS at the ingress/load balancer.

## Next increments

The broader platform roadmap includes Customer, Product, Inventory, Cart, Order, and Payment services. Keycloak remains the identity provider; Customer Service will own customer profiles and map external identities to immutable internal customer IDs. Product Service will own catalog data, Inventory Service will own stock availability, Cart Service will own cart state, Order Service will own order lifecycle, and Payment Service will integrate with payment gateways. These are future phases; this repository currently implements the Order Service and local Keycloak foundation. Kafka with a transactional outbox is a future option for reliable cross-service events, while Redis cache-aside should be introduced when measured traffic justifies it.
