package github.luckygc.cap.replay.jdbc;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

final class RecordingJdbc {
    boolean autoCommit = true;
    boolean committed;
    boolean rolledBack;
    boolean closed;
    @Nullable String sql;
    @Nullable String signature;
    @Nullable Timestamp expiresAt;
    @Nullable SQLException executeFailure;
    @Nullable SQLException commitFailure;
    @Nullable SQLException rollbackFailure;
    @Nullable SQLException restoreFailure;

    DataSource dataSource() {
        return proxy(DataSource.class, this::invokeDataSource);
    }

    private Object invokeDataSource(Method method, @Nullable Object[] arguments) throws Throwable {
        if (method.getName().equals("getConnection")
                && (arguments == null || arguments.length == 0)) {
            return proxy(Connection.class, this::invokeConnection);
        }
        throw new SQLFeatureNotSupportedException(method.getName());
    }

    private Object invokeConnection(Method method, @Nullable Object[] arguments) throws Throwable {
        return switch (method.getName()) {
            case "getAutoCommit" -> autoCommit;
            case "setAutoCommit" -> setAutoCommit((boolean) Objects.requireNonNull(arguments)[0]);
            case "prepareStatement" -> prepare((String) Objects.requireNonNull(arguments)[0]);
            case "commit" -> commit();
            case "rollback" -> rollback();
            case "close" -> closeConnection();
            case "isClosed" -> closed;
            default -> throw new SQLFeatureNotSupportedException(method.getName());
        };
    }

    private Object invokeStatement(Method method, @Nullable Object[] arguments) throws Throwable {
        return switch (method.getName()) {
            case "setString" -> setSignature((String) Objects.requireNonNull(arguments)[1]);
            case "setTimestamp" -> setExpiresAt((Timestamp) Objects.requireNonNull(arguments)[1]);
            case "executeUpdate" -> execute();
            case "close" -> null;
            default -> throw new SQLFeatureNotSupportedException(method.getName());
        };
    }

    private @Nullable Object setAutoCommit(boolean value) throws SQLException {
        if (value && restoreFailure != null) {
            throw restoreFailure;
        }
        autoCommit = value;
        return null;
    }

    private PreparedStatement prepare(String actualSql) {
        sql = actualSql;
        return proxy(PreparedStatement.class, this::invokeStatement);
    }

    private @Nullable Object commit() throws SQLException {
        if (commitFailure != null) {
            throw commitFailure;
        }
        committed = true;
        return null;
    }

    private @Nullable Object rollback() throws SQLException {
        if (rollbackFailure != null) {
            throw rollbackFailure;
        }
        rolledBack = true;
        return null;
    }

    private @Nullable Object closeConnection() {
        closed = true;
        return null;
    }

    private @Nullable Object setSignature(String value) {
        signature = value;
        return null;
    }

    private @Nullable Object setExpiresAt(Timestamp value) {
        expiresAt = value;
        return null;
    }

    private int execute() throws SQLException {
        if (executeFailure != null) {
            throw executeFailure;
        }
        return 1;
    }

    private static <T> T proxy(Class<T> type, MethodCall call) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (ignored, method, arguments) -> call.invoke(method, arguments)));
    }

    @FunctionalInterface
    private interface MethodCall {
        @Nullable Object invoke(Method method, @Nullable Object[] arguments) throws Throwable;
    }
}
