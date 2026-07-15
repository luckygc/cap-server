package github.luckygc.cap.replay.jdbc;

import github.luckygc.cap.NonceConsumer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * 通过数据库唯一约束原子消费 challenge 签名。
 *
 * <p>{@link DataSource#getConnection()} 每次必须返回独立且初始 {@code autoCommit=true}
 * 的连接，不得返回事务感知代理绑定的共享连接。本消费者独立提交或回滚；仅在识别为重复键、安全回滚且恢复自动提交与关闭连接均成功时返回 {@code false}。其余 {@link
 * SQLException}（包括回滚、恢复自动提交或关闭资源失败）会传播给受信宿主，并按 JDBC 与 try-with-resources 的 suppression 语义保留已有异常链。
 */
public final class JdbcNonceConsumer implements NonceConsumer {
    private static final String DEFAULT_TABLE = "cap_consumed_nonces";
    private static final Duration MIN_TTL = Duration.ofMillis(1);
    private static final Duration MAX_TTL = Duration.ofHours(24);
    private static final Pattern TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private final DataSource dataSource;
    private final JdbcDialect dialect;
    private final String insertSql;
    private final Clock clock;

    /**
     * 使用默认表创建消费者。{@link DataSource#getConnection()} 每次必须返回独立且初始 {@code autoCommit=true}
     * 的连接，不得返回事务感知代理绑定的共享连接；本消费者独立提交或回滚。仅在识别为重复键、安全回滚且恢复自动提交与关闭连接均成功时返回 {@code false}。其余 {@link
     * SQLException}（包括回滚、恢复自动提交或关闭资源失败）会传播给受信宿主，并按 JDBC 与 try-with-resources 的 suppression
     * 语义保留已有异常链。
     */
    public JdbcNonceConsumer(DataSource dataSource, JdbcDialect dialect) {
        this(dataSource, dialect, DEFAULT_TABLE);
    }

    /**
     * 使用指定表创建消费者。{@link DataSource#getConnection()} 每次必须返回独立且初始 {@code autoCommit=true}
     * 的连接，不得返回事务感知代理绑定的共享连接；本消费者独立提交或回滚。仅在识别为重复键、安全回滚且恢复自动提交与关闭连接均成功时返回 {@code false}。其余 {@link
     * SQLException}（包括回滚、恢复自动提交或关闭资源失败）会传播给受信宿主，并按 JDBC 与 try-with-resources 的 suppression
     * 语义保留已有异常链。
     */
    public JdbcNonceConsumer(DataSource dataSource, JdbcDialect dialect, String tableName) {
        this(dataSource, dialect, tableName, Clock.systemUTC());
    }

    JdbcNonceConsumer(DataSource dataSource, JdbcDialect dialect, String tableName, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.dialect = Objects.requireNonNull(dialect);
        Objects.requireNonNull(tableName);
        this.clock = Objects.requireNonNull(clock);
        if (!TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("invalid nonce table name");
        }
        insertSql = "INSERT INTO " + tableName + " (signature_hex, expires_at) VALUES (?, ?)";
    }

    @Override
    public boolean consume(String signatureHex, Duration ttl) throws Exception {
        Objects.requireNonNull(signatureHex);
        Objects.requireNonNull(ttl);
        Instant expiresAt = expiresAt(ttl);
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new SQLException("nonce connection must be independent");
            }
            connection.setAutoCommit(false);
            SQLException primaryFailure = null;
            SQLException duplicateCandidate = null;
            try {
                try {
                    try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                        statement.setString(1, signatureHex);
                        statement.setTimestamp(2, Timestamp.from(expiresAt));
                        try {
                            statement.executeUpdate();
                        } catch (SQLException failure) {
                            duplicateCandidate = failure;
                            throw failure;
                        }
                    }
                } catch (SQLException failure) {
                    primaryFailure = failure;
                    rollbackOrSuppress(connection, failure);
                    if (failure == duplicateCandidate
                            && dialect.isDuplicateKey(failure)
                            && failure.getSuppressed().length == 0) {
                        return false;
                    }
                    throw failure;
                }
                try {
                    connection.commit();
                } catch (SQLException failure) {
                    primaryFailure = failure;
                    rollbackOrSuppress(connection, failure);
                    throw failure;
                }
                return true;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException restoreFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(restoreFailure);
                        throw primaryFailure;
                    }
                    throw restoreFailure;
                }
            }
        }
    }

    private Instant expiresAt(Duration ttl) {
        Duration effectiveTtl = ttl.compareTo(MIN_TTL) < 0 ? MIN_TTL : ttl;
        if (effectiveTtl.compareTo(MAX_TTL) > 0) {
            effectiveTtl = MAX_TTL;
        }
        return clock.instant().plus(effectiveTtl);
    }

    private static void rollbackOrSuppress(Connection connection, SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
