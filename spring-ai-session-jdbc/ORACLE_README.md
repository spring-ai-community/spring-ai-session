# Oracle JDBC Session Support

This module includes Oracle support for the JDBC-backed Spring AI
`SessionRepository`.

## What Was Added

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

- Maven dependencies
  - Oracle JDBC dependency:
    - `com.oracle.database.jdbc:ojdbc11`
  - `ojdbc-provider-jackson-oson` is not required for this module.
  - Optional Oracle wallet/security dependencies when needed:
    - `oraclepki`
    - `osdt_core`
    - `osdt_cert`

## Schema Setup

Run the bundled Oracle schema before using the repository:

```sql
@src/main/resources/org/springframework/ai/session/jdbc/schema-oracle.sql
```

With Spring Boot SQL initialization:

```properties
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:org/springframework/ai/session/jdbc/schema-oracle.sql
```

## Required Dependencies

For Oracle projects, include the Oracle JDBC driver:

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
</dependency>
```

If you connect with an Oracle wallet, also include Oracle security dependencies
required by your runtime.

## Manual Repository Usage

```java
SessionRepository repository = JdbcSessionRepository.builder()
        .dataSource(dataSource)
        .dialect(new OracleJdbcSessionRepositoryDialect())
        .build();
```

## Test Configuration

Run all tests:

```bash
mvn test
```

Run only Oracle integration tests:

```bash
mvn -Dtest=OracleJdbcSessionRepositoryTests test
```

Requirements:

```bash
Docker must be running
```

If you are using Podman, set `DOCKER_HOST` to the Podman machine socket before
running tests:

```bash
export DOCKER_HOST=unix:///var/folders/p0/lzh773mj2tz6x70bbwj_wgsm0000gn/T/podman/podman-machine-default-api.sock
```

If IntelliJ fails with `Illegal character in path ... unix:///tmp/podman.sock`,
update the run configuration environment to use the same `DOCKER_HOST` value as
your terminal.

## Notes

- Oracle support expects Oracle Database JSON column support.
- JSON values use Oracle `JSON` columns defined in `schema-oracle.sql`.
- `event_version` is changed by event mutations, not by session upserts.
