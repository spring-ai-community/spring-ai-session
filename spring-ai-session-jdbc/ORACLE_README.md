# Oracle Setup Guide for `spring-ai-session-jdbc`

This guide shows how to run the JDBC session repository with Oracle Database.

## 1. Use an Oracle version that supports `JSON` column type

The bundled schema uses native `JSON` columns (`metadata`, `message_data`), so use Oracle 21c+ (Oracle Free 23c works well).

## 2. Add dependencies

Add the JDBC session module and Oracle JDBC driver to your application:

```xml
<dependencies>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-session-jdbc</artifactId>
        <version>${spring-ai-session-jdbc.version}</version>
    </dependency>

    <dependency>
        <groupId>com.oracle.database.jdbc</groupId>
        <artifactId>ojdbc11</artifactId>
    </dependency>
</dependencies>
```

## 3. Configure your Oracle datasource

Example `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/FREEPDB1
    username: app_user
    password: app_password
    driver-class-name: oracle.jdbc.OracleDriver
```

Use your own host/service/user credentials for your environment.

## 4. Create the session tables

Run the schema script from this module:

`src/main/resources/org/springframework/ai/session/jdbc/schema-oracle.sql`

Example:

```bash
sqlplus app_user/app_password@//localhost:1521/FREEPDB1 @src/main/resources/org/springframework/ai/session/jdbc/schema-oracle.sql
```

The script creates:
- `AI_SESSION`
- `AI_SESSION_EVENT`
- supporting indexes and FK cascade (`ON DELETE CASCADE`)

## 5. Build the repository bean

```java
import javax.sql.DataSource;

import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SessionConfig {

    @Bean
    SessionRepository sessionRepository(DataSource dataSource) {
        return JdbcSessionRepository.builder()
            .dataSource(dataSource)
            .build();
    }
}
```

Dialect is auto-detected from `DataSource` (`Oracle` -> `OracleJdbcSessionRepositoryDialect`).

If you want to force Oracle explicitly:

```java
.dialect(new OracleJdbcSessionRepositoryDialect())
```

## 6. Quick verification

1. Save a session and read it back by ID.
2. Append events and query them with `EventFilter.all()`.
3. Delete the session and confirm events are removed by FK cascade.

## Troubleshooting

- `ORA-00902: invalid datatype` on `JSON`: database version is too old for native JSON type.
- Dialect not detected as Oracle: check datasource URL/driver and database product name.
- Table not found errors: schema script was not applied to the same schema/user used by the app.
