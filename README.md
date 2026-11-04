# Nnomm Flash Deal Service

A backend service built with **Kotlin, Ktor, and PostgreSQL** for managing restaurant flash deals. Customers can reserve limited deal inventory, while payment webhooks confirm or fail reservations.

The service is designed to maintain data consistency under concurrent requests, client retries, duplicate payment events, and simultaneous execution across multiple application instances.

## Tech Stack

- **Language:** Kotlin
- **Backend framework:** Ktor
- **Database:** PostgreSQL 15+
- **Database connection pool:** HikariCP
- **Database migrations:** Flyway
- **Authentication:** JWT (HS256)
- **Payment verification:** HMAC-SHA256
- **Build tool:** Gradle
- **Deployment:** Docker and Docker Compose
- **Testing:** Kotlin test, Ktor test host, PostgreSQL integration tests

## Features

### Deal Management
- Restaurant admins can create deals for their own restaurants.
- Authenticated users can retrieve deal details.
- Deal creation validates title, price, stock, and time range.
- Deal availability is restricted to the interval `[starts_at, ends_at)`.

### Claim Management
- Customers can reserve between 1 and 3 units per request.
- Stock is decremented atomically during claim creation.
- A customer can have only one active claim per deal.
- Reservations expire after the configured claim TTL.
- Expired reservations return their units to available stock.

### Idempotency
- Claim requests require an `Idempotency-Key`.
- Keys are scoped to individual customers.
- Repeated requests with the same key and payload replay the original response.
- Reusing a key with a different payload returns `IDEMPOTENCY_KEY_REUSED`.
- In-progress requests are protected against duplicate execution.

### Payment Webhooks
- Payment events are verified using HMAC-SHA256 signatures.
- Webhook signatures cover the timestamp and exact raw request body.
- Timestamp validation rejects requests outside the 300-second tolerance.
- Duplicate event IDs are processed only once.
- Payment and expiry transitions are coordinated using PostgreSQL row locks.

### Authentication and Authorization
- JWT authentication using HS256.
- Supports `customer` and `restaurant_admin` roles.
- Restaurant admins can create deals only for their own restaurant.
- Customers can access their own claims.
- Restaurant admins can access claims belonging to their restaurant.

## Project Setup

### Prerequisites

- JDK 21
- Docker Desktop / Docker Engine
- Docker Compose
- Git

### Environment Variables

Create a `.env` file in the project root.

```env
DATABASE_URL=jdbc:postgresql://postgres:5432/flash_deal
DATABASE_USER=flash_user
DATABASE_PASSWORD=your_database_password

JWT_SECRET=your_jwt_secret
WEBHOOK_SECRET=your_webhook_secret

CLAIM_TTL_SECONDS=600

POSTGRES_DB=flash_deal
POSTGRES_USER=flash_user
POSTGRES_PASSWORD=your_database_password
```

Use sufficiently strong, randomly generated secrets. Do not commit `.env` or expose credentials in logs.

### Run with Docker Compose

Build and start PostgreSQL and both application instances:

```bash
docker compose up --build
```

The service instances share the same PostgreSQL database.

| Service | URL |
|---|---|
| Application instance 1 | http://localhost:8080 |
| Application instance 2 | http://localhost:8081 |
| PostgreSQL | localhost:5433 |

To stop the services:

```bash
docker compose down
```

To stop services and remove the database volume:

```bash
docker compose down -v
```

**Note:** Removing the volume deletes the persisted PostgreSQL data.

## Running Tests

Run the complete test suite:

```bash
./gradlew test
```

On Windows PowerShell:

```powershell
.\gradlew.bat test
```

The integration and concurrency tests use real PostgreSQL rather than an in-memory database.

The test suite covers:

- Concurrent claim requests and stock consistency.
- Concurrent requests using the same idempotency key.
- Multiple claim attempts by the same customer using different keys.
- Reservation expiry and stock restoration.
- Payment success and failure handling.
- Duplicate and out-of-order webhook events.
- Invalid webhook signatures and stale timestamps.
- Payment versus expiry concurrency.
- JWT authentication and role-based authorization.
- Claim visibility restrictions.

GitHub Actions runs the build and test suite using a PostgreSQL 15 service.

## API Endpoints

All endpoints except the payment webhook require:

```http
Authorization: Bearer <JWT>
```

| Method | Endpoint | Access | Description |
|---|---|---|---|
| POST | `/v1/deals` | Restaurant admin | Create a deal |
| GET | `/v1/deals/{dealId}` | Authenticated user | Retrieve deal details |
| POST | `/v1/deals/{dealId}/claims` | Customer | Reserve deal units |
| GET | `/v1/claims/{claimId}` | Claim owner / restaurant admin | Retrieve claim details |
| POST | `/v1/webhooks/payments` | Payment provider | Process a signed payment event |

### Create a Deal

```http
POST /v1/deals
Content-Type: application/json
```

```json
{
  "title": "Chicken Biryani Box",
  "price_paise": 9900,
  "stock_total": 50,
  "starts_at": "2026-11-05T12:00:00Z",
  "ends_at": "2026-11-05T14:00:00Z"
}
```

### Create a Claim

```http
POST /v1/deals/{dealId}/claims
Authorization: Bearer <CUSTOMER_JWT>
Idempotency-Key: unique-request-key
Content-Type: application/json
```

```json
{
  "quantity": 2
}
```

### Retrieve a Claim

```http
GET /v1/claims/{claimId}
Authorization: Bearer <JWT>
```

### Payment Webhook

```http
POST /v1/webhooks/payments
X-Payment-Signature: t=<unix_timestamp>,v1=<hex_signature>
Content-Type: application/json
```

Example request body:

```json
{
  "event_id": "evt_123456",
  "type": "payment.succeeded",
  "claim_id": "9d40...",
  "amount_paise": 19800,
  "occurred_at": "2026-11-05T12:03:11Z"
}
```

The signature is calculated using HMAC-SHA256 with `WEBHOOK_SECRET` over:

```text
<timestamp>.<raw_request_body>
```

## Claim Lifecycle

The service supports five claim statuses:

| Status | Meaning |
|---|---|
| `RESERVED` | Units are held while payment is pending |
| `PAID` | Payment succeeded and the amount matched |
| `EXPIRED` | Reservation expired before payment |
| `NEEDS_REVIEW` | Payment succeeded but the amount did not match |
| `REFUND_PENDING` | Payment arrived after the reservation expired |

A reservation holds inventory until it is paid or expires. Payment processing and the expiry worker coordinate their database operations so that stock is not restored twice.

## Design and Consistency Decisions

- PostgreSQL transactions are used to keep related database changes atomic.
- Row-level locking serializes operations on the same deal and claim across application instances.
- Stock updates are performed atomically to prevent overselling.
- Database constraints protect important uniqueness and data-integrity rules.
- Payment event insertion and claim state updates happen in the same transaction.
- Idempotency outcomes are persisted so that retries return the original result.

See [DESIGN.md](DESIGN.md) for detailed explanations of concurrency control, transaction isolation, failure scenarios, webhook security, database constraints, and scaling considerations.

## Assumptions

- All monetary values are represented as integer paise; floating-point arithmetic is not used.
- All API timestamps are expected in UTC.
- A payment success received after a reservation expires results in `REFUND_PENDING`; expired inventory is not reserved again.
- A payment amount mismatch results in `NEEDS_REVIEW`, and the associated units remain held until reviewed.
- Unknown claim references in valid signed payment events are recorded and acknowledged with HTTP 200 to avoid repeated provider retries.
- Duplicate `event_id` values are acknowledged without applying their effects again.
- New events for claims already in terminal states do not reverse an established outcome.
- A failed payment does not release the reservation; the customer may retry payment while the reservation remains active.

## Future Improvements

Given more development time, I would consider:

- Cursor-based pagination for customer claim history.
- Distributed rate limiting across application instances.
- Structured JSON logging with request IDs.
- Prometheus metrics and monitoring.
- An admin endpoint for listing claims with status filters.
- Improved observability around payment reconciliation and refund processing.

## AI Tools

**ChatGPT** was used as a development assistant for:
- Understanding the assignment requirements and breaking them into implementation steps.
- Discussing PostgreSQL concurrency, transactions, and row-level locking.
- Reviewing implementation approaches and debugging test failures.
- Understanding webhook signature verification and idempotency.
- Reviewing test coverage and CI configuration.

All implementation decisions and code were reviewed and tested during development.
