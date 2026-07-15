package github.luckygc.cap.replay.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

final class StoreIntegrationDataSource implements DataSource {
    private final String url;

    StoreIntegrationDataSource(String url) {
        this.url = url;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        throw unsupported();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw unsupported();
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        throw unsupported();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw unsupported();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw unsupported();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        throw unsupported();
    }

    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException(
                "unsupported store-integration DataSource method");
    }
}
