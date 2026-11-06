package com.wherelee.helpdesk.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Sets the PostgreSQL session variables consumed by the RLS policies on every
 * connection checkout, and clears them before the connection returns to the pool.
 * This keeps isolation working even with a shared, pooled connection.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private Connection prepare(Connection connection) throws SQLException {
        reset(connection);
        TenantScope scope = TenantContext.get();
        if (scope != null) {
            if (scope.platformAdmin()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT set_config('app.platform_admin', 'true', false)")) {
                    ps.execute();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT set_config('app.tenant_id', ?, false)")) {
                    ps.setString(1, scope.tenantId().toString());
                    ps.execute();
                }
            }
        }
        return wrap(connection);
    }

    private void reset(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET app.tenant_id");
            statement.execute("RESET app.platform_admin");
        }
    }

    private Connection wrap(Connection delegate) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                try {
                    reset(delegate);
                } catch (SQLException ignored) {
                    // closing must not be blocked by a failed reset
                }
                delegate.close();
                return null;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler
        );
    }
}
