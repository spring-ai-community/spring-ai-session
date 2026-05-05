# Spring AI Session JDBC Auto-Configuration

Auto-configuration module for JDBC-backed Spring AI session repositories.

## Oracle Support

The auto-configuration detects Oracle through the JDBC dialect and will create an
`OracleJdbcSessionRepository` when Oracle OSON support classes are available on the
classpath.

If Oracle is detected but OSON classes are missing, it falls back to the generic
`JdbcSessionRepository`.

### Runtime Dependencies (Oracle)

Add Oracle JDBC and OSON provider dependencies in your application:

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

### Spring Properties Example

```properties
spring.datasource.url=jdbc:oracle:thin:@//localhost:1521/FREEPDB1
spring.datasource.username=app
spring.datasource.password=app
spring.ai.session.jdbc.initialize-schema=always
```

## Oracle Integration Tests

Oracle integration tests are implemented in:

- `src/test/java/org/springaicommunity/session/jdbc/autoconfigure/JdbcSessionRepositoryOracleAutoConfigurationIT.java`

The tests use Testcontainers with:

- image: `gvenzl/oracle-free:23-slim-faststart`
- module: `org.testcontainers:testcontainers-oracle-free`

Run only Oracle integration tests:

```bash
mvn -Dtest=JdbcSessionRepositoryOracleAutoConfigurationIT test
```

Run the full test suite:

```bash
mvn test
```
