# Oracle JDBC Session Support

This module now includes Oracle support for the JDBC-backed Spring AI
`SessionRepository`.

## What Was Added

- `OracleJdbcSessionRepository`
  - Oracle-specific repository implementation.
  - Stores and reads JSON columns using Oracle OSON-aware JDBC binding.
  - Uses `JsonMapper` with `OsonFactory` by default.
  - Supports the same session and event operations as the generic JDBC repository:
    save, find, delete, append events, replace events, optimistic event replacement,
    event version lookup, filtering, paging, branch filtering, and keyword search.

- `OracleJdbcSessionRepositoryDialect`
  - Adds Oracle SQL dialect support.
  - Uses `MERGE INTO ... USING dual` for session upserts.
  - Uses Oracle pagination syntax with `FETCH FIRST`, `OFFSET`, and `FETCH NEXT`.
  - Adds Oracle-compatible keyword filtering.

- `schema-oracle.sql`
  - Adds Oracle DDL for:
    - `AI_SESSION`
    - `AI_SESSION_EVENT`
  - Uses Oracle `JSON` columns for session metadata, event message data, and event
    metadata.
  - Adds indexes for user lookup, expiration lookup, and event lookup by session and
    timestamp.

- Dialect auto-detection
  - `JdbcSessionRepositoryDialect.from(DataSource)` now detects Oracle and returns
    `OracleJdbcSessionRepositoryDialect`.

- Auto-configuration support
  - When Oracle is detected, JDBC session auto-configuration creates
    `OracleJdbcSessionRepository` if the Oracle OSON classes are available.
  - If the OSON classes are missing, it falls back to the generic
    `JdbcSessionRepository` and logs a warning.

- Maven dependencies
  - Adds optional Oracle JDBC dependencies:
    - `com.oracle.database.jdbc:ojdbc11`
    - `com.oracle.database.jdbc:ojdbc-provider-jackson-oson`
    - `javax.json:javax.json-api`
  - Adds Oracle wallet/security dependencies:
    - `oraclepki`
    - `osdt_core`
    - `osdt_cert`

- Tests
  - Adds Oracle repository integration tests covering session lifecycle, event
    append/replace behavior, filters, paging, expiration lookup, delete behavior, and
    optimistic version checks.
  - Adds Oracle auto-configuration integration coverage using an Oracle container.

## Schema Setup

Run the bundled Oracle schema before using the repository:

```sql
@src/main/resources/org/springframework/ai/session/jdbc/schema-oracle.sql
```

With Spring Boot SQL initialization, configure:

```properties
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:org/springframework/ai/session/jdbc/schema-oracle.sql
```

For managed environments, the same schema can be applied with Flyway, Liquibase, or
your normal database migration process.

## Required Dependencies

For Oracle projects, include the Oracle JDBC driver and OSON provider:

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc-provider-jackson-oson</artifactId>
</dependency>
```

If you connect with an Oracle wallet, also include the Oracle security dependencies
required by your runtime.

## Manual Repository Usage

```java
SessionRepository repository = OracleJdbcSessionRepository.builder()
        .dataSource(dataSource)
        .build();
```

The builder defaults to `OracleJdbcSessionRepositoryDialect` and an OSON-backed
`JsonMapper`.

## Test Configuration

Oracle integration tests run with Testcontainers using Oracle Free:

```bash
mvn -Dtest=OracleJdbcSessionRepositoryTests test
```

Requirements:

```bash
Docker must be running
```

## Notes

- Oracle support expects Oracle Database JSON column support.
- JSON values are bound as Oracle OSON payloads in the Oracle-specific repository.
- `event_version` is only changed by event mutations, not by session upserts.
