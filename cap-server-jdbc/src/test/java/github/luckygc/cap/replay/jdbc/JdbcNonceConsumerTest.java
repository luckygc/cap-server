package github.luckygc.cap.replay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JDBC 防重放测试")
class JdbcNonceConsumerTest {
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("默认 SQL 使用固定 nonce 表")
    void usesDefaultTable() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThat(consumer.consume("signature", Duration.ofSeconds(30))).isTrue();
        assertThat(jdbc.sql)
                .isEqualTo(
                        "INSERT INTO cap_consumed_nonces (signature_hex, expires_at) VALUES (?, ?)");
    }

    @Test
    @DisplayName("接受按标识符组成的限定表名")
    void acceptsQualifiedTableName() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(
                        jdbc.dataSource(), JdbcDialect.POSTGRESQL, "tenant_a.cap_consumed_nonces");

        assertThat(consumer.consume("signature", Duration.ofSeconds(30))).isTrue();
        assertThat(jdbc.sql)
                .isEqualTo(
                        "INSERT INTO tenant_a.cap_consumed_nonces (signature_hex, expires_at) VALUES (?, ?)");
    }

    @Test
    @DisplayName("拒绝不安全表名且异常不回显输入")
    void rejectsUnsafeTableNamesWithoutEchoing() {
        RecordingJdbc jdbc = new RecordingJdbc();

        for (String tableName : new String[] {"", "a b", "a;drop", "a--x", "a.\"b\""}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new JdbcNonceConsumer(
                                            jdbc.dataSource(), JdbcDialect.POSTGRESQL, tableName))
                    .withMessage("invalid nonce table name");
        }
    }

    @Test
    @DisplayName("成功插入后提交、恢复并关闭独立连接")
    void commitsAndRestoresSuccessfulInsert() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(
                        jdbc.dataSource(),
                        JdbcDialect.POSTGRESQL,
                        "cap_consumed_nonces",
                        FIXED_CLOCK);

        assertThat(consumer.consume("signature", Duration.ofSeconds(30))).isTrue();
        assertThat(jdbc.signature).isEqualTo("signature");
        assertThat(jdbc.expiresAt).isEqualTo(Timestamp.from(Instant.parse("2026-07-15T00:00:30Z")));
        assertThat(jdbc.committed).isTrue();
        assertThat(jdbc.autoCommit).isTrue();
        assertThat(jdbc.closed).isTrue();
        assertThat(jdbc.calls)
                .containsExactly(
                        "getAutoCommit",
                        "setAutoCommit(false)",
                        "prepareStatement",
                        "setString",
                        "setTimestamp",
                        "executeUpdate",
                        "statement.close",
                        "commit",
                        "setAutoCommit(true)",
                        "connection.close");
        assertThat(jdbc.callCount("commit")).isOne();
        assertThat(jdbc.callCount("rollback")).isZero();
        assertThat(jdbc.callCount("statement.close")).isOne();
        assertThat(jdbc.callCount("connection.close")).isOne();
    }

    @Test
    @DisplayName("只有 executeUpdate 抛出的重复键才能返回重放")
    void duplicateShapedFailuresBeforeExecuteFailClosed() {
        SQLException prepareFailure = new SQLException("prepare", "23505", 0);
        RecordingJdbc prepareJdbc = new RecordingJdbc();
        prepareJdbc.prepareFailure = prepareFailure;
        assertFailureIsRethrown(prepareJdbc, JdbcDialect.POSTGRESQL, prepareFailure);
        assertThat(prepareJdbc.callCount("executeUpdate")).isZero();

        SQLException stringBindFailure = new SQLException("bind", "23000", 1062);
        RecordingJdbc stringBindJdbc = new RecordingJdbc();
        stringBindJdbc.signatureBindFailure = stringBindFailure;
        assertFailureIsRethrown(stringBindJdbc, JdbcDialect.MYSQL, stringBindFailure);
        assertThat(stringBindJdbc.callCount("executeUpdate")).isZero();

        SQLException timestampBindFailure = new SQLException("bind", "23000", 1062);
        RecordingJdbc timestampBindJdbc = new RecordingJdbc();
        timestampBindJdbc.expiresAtBindFailure = timestampBindFailure;
        assertFailureIsRethrown(timestampBindJdbc, JdbcDialect.MARIADB, timestampBindFailure);
        assertThat(timestampBindJdbc.callCount("executeUpdate")).isZero();
    }

    @Test
    @DisplayName("statement close 的重复键形状异常必须 fail closed")
    void duplicateShapedStatementCloseFailureFailsClosed() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException failure = new SQLException("statement close", "23505", 0);
        jdbc.statementCloseFailure = failure;

        assertFailureIsRethrown(jdbc, JdbcDialect.POSTGRESQL, failure);
        assertThat(jdbc.callCount("executeUpdate")).isOne();
        assertThat(jdbc.callCount("statement.close")).isOne();
        assertThat(jdbc.callCount("commit")).isZero();
    }

    @Test
    @DisplayName("execute duplicate 后 statement close 失败保留 suppressed 并 fail closed")
    void statementCloseFailurePreventsExecuteDuplicateReplayResult() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException duplicate = new SQLException("duplicate", "23505", 0);
        SQLException closeFailure = new SQLException("statement close");
        jdbc.executeFailure = duplicate;
        jdbc.statementCloseFailure = closeFailure;
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(duplicate);
        assertThat(duplicate.getSuppressed()).containsExactly(closeFailure);
        assertThat(jdbc.calls)
                .containsSubsequence(
                        "executeUpdate",
                        "statement.close",
                        "rollback",
                        "setAutoCommit(true)",
                        "connection.close");
        assertThat(jdbc.callCount("commit")).isZero();
    }

    @Test
    @DisplayName("connection close 失败原样抛出且不掩盖已提交事实")
    void rethrowsConnectionCloseFailureAfterCommit() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException closeFailure = new SQLException("connection close", "23505", 0);
        jdbc.connectionCloseFailure = closeFailure;
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(closeFailure);
        assertThat(jdbc.committed).isTrue();
        assertThat(jdbc.autoCommit).isTrue();
        assertThat(jdbc.closed).isTrue();
        assertThat(jdbc.callCount("commit")).isOne();
        assertThat(jdbc.callCount("connection.close")).isOne();
    }

    @Test
    @DisplayName("拒绝已加入事务的连接且不准备 SQL")
    void rejectsConnectionWithoutAutoCommitBeforePreparing() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.autoCommit = false;
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThatThrownBy(() -> consumer.consume("signature", Duration.ofSeconds(30)))
                .isInstanceOf(SQLException.class)
                .hasMessage("nonce connection must be independent");
        assertThat(jdbc.sql).isNull();
        assertThat(jdbc.closed).isTrue();
    }

    @Test
    @DisplayName("三种方言的重复键均回滚并返回重放")
    void rollsBackDuplicateKeyAndReturnsFalse() throws Exception {
        for (JdbcDialect dialect : JdbcDialect.values()) {
            RecordingJdbc jdbc = new RecordingJdbc();
            jdbc.executeFailure = duplicateFailure(dialect);
            JdbcNonceConsumer consumer = new JdbcNonceConsumer(jdbc.dataSource(), dialect);

            assertThat(consumer.consume("signature", Duration.ofSeconds(30))).isFalse();
            assertThat(jdbc.rolledBack).isTrue();
            assertThat(jdbc.autoCommit).isTrue();
            assertThat(jdbc.closed).isTrue();
        }
    }

    @Test
    @DisplayName("非重复约束错误回滚后原样抛出")
    void rollsBackAndRethrowsUnrelatedConstraintFailure() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException failure = new SQLException("constraint", "23000", 1048);
        jdbc.executeFailure = failure;
        JdbcNonceConsumer consumer = new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.MYSQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(failure);
        assertThat(jdbc.rolledBack).isTrue();
        assertThat(jdbc.autoCommit).isTrue();
        assertThat(jdbc.closed).isTrue();
    }

    @Test
    @DisplayName("提交失败回滚并抛出而不分类为重放")
    void rollsBackAndRethrowsCommitFailure() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException failure = new SQLException("commit", "23505", 0);
        jdbc.commitFailure = failure;
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(failure);
        assertThat(jdbc.rolledBack).isTrue();
        assertThat(jdbc.committed).isFalse();
        assertThat(jdbc.autoCommit).isTrue();
        assertThat(jdbc.closed).isTrue();
    }

    @Test
    @DisplayName("重复键回滚失败时保留主异常并禁止重放结果")
    void suppressesRollbackFailureOntoDuplicateInsertFailure() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException failure = new SQLException("duplicate", "23505", 0);
        SQLException rollbackFailure = new SQLException("rollback");
        jdbc.executeFailure = failure;
        jdbc.rollbackFailure = rollbackFailure;
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(rollbackFailure);
        assertThat(jdbc.autoCommit).isTrue();
        assertThat(jdbc.closed).isTrue();
    }

    @Test
    @DisplayName("提交回滚失败时保留提交异常和 suppressed")
    void suppressesRollbackFailureOntoCommitFailure() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException failure = new SQLException("commit");
        SQLException rollbackFailure = new SQLException("rollback");
        jdbc.commitFailure = failure;
        jdbc.rollbackFailure = rollbackFailure;
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(rollbackFailure);
        assertThat(jdbc.autoCommit).isTrue();
        assertThat(jdbc.closed).isTrue();
    }

    @Test
    @DisplayName("提交成功后的恢复失败仍然抛出")
    void throwsRestoreFailureAfterSuccessfulCommit() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException restoreFailure = new SQLException("restore");
        jdbc.restoreFailure = restoreFailure;
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(restoreFailure);
        assertThat(jdbc.committed).isTrue();
        assertThat(jdbc.autoCommit).isFalse();
        assertThat(jdbc.closed).isTrue();
    }

    @Test
    @DisplayName("主异常发生后恢复失败作为 suppressed 保留")
    void suppressesRestoreFailureOntoPrimaryFailure() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException failure = new SQLException("constraint", "23000", 1048);
        SQLException restoreFailure = new SQLException("restore");
        jdbc.executeFailure = failure;
        jdbc.restoreFailure = restoreFailure;
        JdbcNonceConsumer consumer = new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.MYSQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(restoreFailure);
        assertThat(jdbc.rolledBack).isTrue();
        assertThat(jdbc.closed).isTrue();
    }

    @Test
    @DisplayName("重复键恢复失败时 fail closed 并保留主异常")
    void restoreFailurePreventsReplayResult() {
        RecordingJdbc jdbc = new RecordingJdbc();
        SQLException failure = new SQLException("duplicate", "23505", 0);
        SQLException restoreFailure = new SQLException("restore");
        jdbc.executeFailure = failure;
        jdbc.restoreFailure = restoreFailure;
        JdbcNonceConsumer consumer =
                new JdbcNonceConsumer(jdbc.dataSource(), JdbcDialect.POSTGRESQL);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(restoreFailure);
        assertThat(jdbc.rolledBack).isTrue();
        assertThat(jdbc.closed).isTrue();
    }

    @Test
    @DisplayName("TTL 最少存储一毫秒且最多二十四小时")
    void clampsStoredTtlWithoutOverflow() throws Exception {
        RecordingJdbc nanosJdbc = new RecordingJdbc();
        JdbcNonceConsumer nanosConsumer =
                new JdbcNonceConsumer(
                        nanosJdbc.dataSource(),
                        JdbcDialect.POSTGRESQL,
                        "cap_consumed_nonces",
                        FIXED_CLOCK);

        assertThat(nanosConsumer.consume("nanos", Duration.ofNanos(1))).isTrue();
        assertThat(nanosJdbc.expiresAt)
                .isEqualTo(Timestamp.from(Instant.parse("2026-07-15T00:00:00.001Z")));

        RecordingJdbc hugeJdbc = new RecordingJdbc();
        JdbcNonceConsumer hugeConsumer =
                new JdbcNonceConsumer(
                        hugeJdbc.dataSource(),
                        JdbcDialect.POSTGRESQL,
                        "cap_consumed_nonces",
                        FIXED_CLOCK);

        assertThat(hugeConsumer.consume("huge", Duration.ofSeconds(Long.MAX_VALUE))).isTrue();
        assertThat(hugeJdbc.expiresAt)
                .isEqualTo(Timestamp.from(Instant.parse("2026-07-16T00:00:00Z")));
    }

    private static SQLException duplicateFailure(JdbcDialect dialect) {
        return dialect == JdbcDialect.POSTGRESQL
                ? new SQLException("duplicate", "23505", 0)
                : new SQLException("duplicate", "23000", 1062);
    }

    private static void assertFailureIsRethrown(
            RecordingJdbc jdbc, JdbcDialect dialect, SQLException failure) {
        JdbcNonceConsumer consumer = new JdbcNonceConsumer(jdbc.dataSource(), dialect);

        assertThat(catchThrowable(() -> consumer.consume("signature", Duration.ofSeconds(30))))
                .isSameAs(failure);
        assertThat(jdbc.rolledBack).isTrue();
        assertThat(jdbc.autoCommit).isTrue();
        assertThat(jdbc.closed).isTrue();
        assertThat(jdbc.callCount("rollback")).isOne();
        assertThat(jdbc.callCount("connection.close")).isOne();
    }
}
