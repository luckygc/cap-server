package github.luckygc.cap.replay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

@DisplayName("JDBC 真实存储互操作")
class JdbcNonceConsumerStoreIT {
    private static final int CONCURRENCY = 32;
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String CREATE_TABLE =
            "CREATE TABLE cap_consumed_nonces ("
                    + "signature_hex VARCHAR(128) PRIMARY KEY, "
                    + "expires_at TIMESTAMP(6) NOT NULL, "
                    + "CHECK (signature_hex <> 'constraint_failure'))";

    @TestFactory
    @DisplayName("三种数据库均保持原子消费与错误分类")
    Stream<DynamicTest> databasesPreserveAtomicConsumptionAndErrorClassification() {
        return Stream.of(
                database(
                        "PostgreSQL 17.10",
                        "jdbc:tc:postgresql:17.10-alpine:///cap?TC_DAEMON=true",
                        JdbcDialect.POSTGRESQL),
                database(
                        "MySQL 8.4.10",
                        "jdbc:tc:mysql:8.4.10:///cap?TC_DAEMON=true",
                        JdbcDialect.MYSQL),
                database(
                        "MariaDB 11.4.12",
                        "jdbc:tc:mariadb:11.4.12:///cap?TC_DAEMON=true",
                        JdbcDialect.MARIADB));
    }

    private static DynamicTest database(String name, String url, JdbcDialect dialect) {
        return DynamicTest.dynamicTest(name, () -> verifyDatabase(url, dialect));
    }

    private static void verifyDatabase(String url, JdbcDialect dialect) throws Exception {
        DataSource dataSource = availableDataSource(url);
        recreateTable(dataSource);
        JdbcNonceConsumer consumer = new JdbcNonceConsumer(dataSource, dialect);
        String signature = syntheticSignature();

        assertThat(concurrentResults(consumer, signature)).containsExactlyInAnyOrder(successes());
        assertThat(consumer.consume(signature, TTL)).isFalse();
        assertThat(consumer.consume(syntheticSignature(), TTL)).isTrue();
        assertThatThrownBy(() -> consumer.consume("constraint_failure", TTL))
                .isInstanceOf(SQLException.class);
    }

    private static DataSource availableDataSource(String url) {
        DataSource dataSource = new StoreIntegrationDataSource(url);
        try (Connection ignored = dataSource.getConnection()) {
            return dataSource;
        } catch (SQLException | RuntimeException failure) {
            throw dockerUnavailable();
        }
    }

    private static void recreateTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cap_consumed_nonces");
            statement.executeUpdate(CREATE_TABLE);
        }
    }

    private static List<Boolean> concurrentResults(JdbcNonceConsumer consumer, String signature) {
        return StoreIntegrationSupport.runConcurrently(
                CONCURRENCY, () -> consumer.consume(signature, TTL));
    }

    private static Boolean[] successes() {
        Boolean[] expected = new Boolean[CONCURRENCY];
        expected[0] = true;
        for (int index = 1; index < CONCURRENCY; index++) {
            expected[index] = false;
        }
        return expected;
    }

    private static String syntheticSignature() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static IllegalStateException dockerUnavailable() {
        return new IllegalStateException(
                "store-integration category=docker_unavailable; start Docker and rerun "
                        + "mise exec maven -- mvn -Pstore-integration verify");
    }
}
