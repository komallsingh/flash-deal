# flashdeal

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:
 * [Ktor Documentation](https://ktor.io/docs/home.html)
 * [Ktor GitHub page](https://github.com/ktorio/ktor)
 * [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).


## Features
Here's a list of features included in this project:

| Name | Description |
|------|-------------|
| [Call Logging](https://start.ktor.io/p/io.ktor/server-call-logging) | Logs client requests |
| [Status Pages](https://start.ktor.io/p/io.ktor/server-status-pages) | Provides exception handling for routes |
| [Content Negotiation](https://start.ktor.io/p/io.ktor/server-content-negotiation) | Provides automatic content conversion according to Content-Type and Accept headers |
| [kotlinx.serialization](https://start.ktor.io/p/io.ktor/server-kotlinx-serialization) | Handles JSON serialization using kotlinx.serialization library |
| [Request Validation](https://start.ktor.io/p/io.ktor/server-request-validation) | Adds validation for incoming requests |
| [AutoHeadResponse](https://start.ktor.io/p/io.ktor/server-auto-head-response) | Provides automatic responses for HEAD requests |


## Building & Running
Create a local `.env` from `.env.example`, set a private `DATABASE_PASSWORD`, and
start PostgreSQL with `docker compose up -d`. Docker Compose reads `.env`
automatically; the Gradle `run` and `test` tasks also load the database settings
from `.env`, taking precedence over inherited shell or Gradle daemon values.
Those tasks use the canonical `Asia/Kolkata` JVM timezone so PostgreSQL accepts
the JDBC startup timezone on Windows.
The tests require the running PostgreSQL container; `DatabaseConcurrencyTest`
uses two independent connection pools to verify that simultaneous reservations
cannot oversell the last unit of stock.

The database is published on host port `5433` to avoid conflicting with a
Windows PostgreSQL service on `5432`; the container continues to listen on
`5432`. The PostgreSQL data volume persists across container recreation.
Changing `DATABASE_PASSWORD` in `.env` does not change the password of a
database that was already initialized; update the existing PostgreSQL role
password deliberately if necessary.

To build or run the project, use one of the following tasks:


| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Build the project |
| `./gradlew run`     | Run the server    |

If the server starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```
