package github.luckygc.cap.replay.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JDBC 方言测试")
class JdbcDialectTest {

    @Test
    @DisplayName("PostgreSQL 只按唯一约束 SQLState 分类")
    void classifiesPostgresqlDuplicateKey() {
        assertThat(JdbcDialect.POSTGRESQL.isDuplicateKey(new SQLException("duplicate", "23505", 0)))
                .isTrue();
        assertThat(JdbcDialect.POSTGRESQL.isDuplicateKey(new SQLException("other", "23000", 1062)))
                .isFalse();
    }

    @Test
    @DisplayName("MySQL 与 MariaDB 同时匹配 SQLState 和错误码")
    void classifiesMysqlAndMariadbDuplicateKey() {
        SQLException duplicate = new SQLException("duplicate", "23000", 1062);

        assertThat(JdbcDialect.MYSQL.isDuplicateKey(duplicate)).isTrue();
        assertThat(JdbcDialect.MARIADB.isDuplicateKey(duplicate)).isTrue();
        assertThat(JdbcDialect.MYSQL.isDuplicateKey(new SQLException("constraint", "23000", 1048)))
                .isFalse();
    }

    @Test
    @DisplayName("沿驱动异常链识别重复键")
    void classifiesDuplicateKeyInExceptionChain() {
        SQLException wrapper = new SQLException("batch", "HY000", 0);
        wrapper.setNextException(new SQLException("duplicate", "23505", 0));

        assertThat(JdbcDialect.POSTGRESQL.isDuplicateKey(wrapper)).isTrue();
    }
}
