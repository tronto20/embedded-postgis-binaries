package io.zonky.test.postgres.verification;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

public final class PostgisConsumerSmokeMain {

    private PostgisConsumerSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        try (EmbeddedPostgres postgres = startPostgres();
             Connection connection = postgres.getPostgresDatabase().getConnection()) {
            execute(connection, "CREATE EXTENSION postgis");
            expectScalar(connection, "SELECT PostGIS_Lib_Version()", "3.6.2");
            expectScalar(connection,
                    "SELECT ST_AsText(ST_Transform('SRID=4326;POINT(0 0)'::geometry, 3857))",
                    "POINT(0 0)");
            expectBoolean(connection,
                    "SELECT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'postgis_raster')",
                    false);

            System.out.println("Embedded Postgres consumer smoke test passed.");
        }
    }

    private static EmbeddedPostgres startPostgres() throws IOException {
        return EmbeddedPostgres.builder()
                .setPGStartupWait(Duration.ofSeconds(30))
                .setErrorRedirector(Redirect.INHERIT)
                .setOutputRedirector(Redirect.INHERIT)
                .start();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void expectScalar(Connection connection, String sql, String expected) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("No rows returned for query: " + sql);
            }

            String actual = resultSet.getString(1);
            if (!expected.equals(actual)) {
                throw new IllegalStateException(
                        "Unexpected result for query [" + sql + "], expected [" + expected + "] but was [" + actual + "]"
                );
            }

            if (resultSet.next()) {
                throw new IllegalStateException("More than one row returned for query: " + sql);
            }
        }
    }

    private static void expectBoolean(Connection connection, String sql, boolean expected) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("No rows returned for query: " + sql);
            }

            boolean actual = resultSet.getBoolean(1);
            if (expected != actual) {
                throw new IllegalStateException(
                        "Unexpected result for query [" + sql + "], expected [" + expected + "] but was [" + actual + "]"
                );
            }

            if (resultSet.next()) {
                throw new IllegalStateException("More than one row returned for query: " + sql);
            }
        }
    }
}
