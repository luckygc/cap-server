package github.luckygc.cap.replay.jdbc;

import java.sql.SQLException;

/** JDBC 重复键错误分类方言。 */
public enum JdbcDialect {
    POSTGRESQL {
        @Override
        boolean isDuplicateKey(SQLException exception) {
            return hasError(exception, "23505", 0, false);
        }
    },
    MYSQL {
        @Override
        boolean isDuplicateKey(SQLException exception) {
            return hasError(exception, "23000", 1062, true);
        }
    },
    MARIADB {
        @Override
        boolean isDuplicateKey(SQLException exception) {
            return hasError(exception, "23000", 1062, true);
        }
    };

    abstract boolean isDuplicateKey(SQLException exception);

    private static boolean hasError(
            SQLException exception, String sqlState, int errorCode, boolean matchErrorCode) {
        for (SQLException current = exception;
                current != null;
                current = current.getNextException()) {
            if (sqlState.equals(current.getSQLState())
                    && (!matchErrorCode || current.getErrorCode() == errorCode)) {
                return true;
            }
        }
        return false;
    }
}
