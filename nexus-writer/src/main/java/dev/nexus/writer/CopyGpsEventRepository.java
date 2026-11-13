package dev.nexus.writer;

import dev.nexus.common.Ping;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * Idempotent batch write: COPY into a per-connection temporary staging table, then
 * {@code INSERT ... SELECT ... ON CONFLICT DO NOTHING} into the hypertable, in one transaction.
 *
 * <p>COPY alone cannot skip duplicates (one duplicate key fails the whole COPY), which is why the
 * staging step exists. The staging table is created once per physical connection by the pool's
 * init SQL ({@link #STAGING_DDL}) and emptied by {@code ON COMMIT DELETE ROWS}; creating a temp
 * table per batch would churn the system catalogue at thousands of batches per hour.
 */
public final class CopyGpsEventRepository implements GpsEventRepository {

    public static final String STAGING_DDL = """
            CREATE TEMP TABLE IF NOT EXISTS gps_staging (
                vehicle_id  text,
                event_ts    timestamptz,
                event_id    uuid,
                fleet_id    text,
                lat         double precision,
                lon         double precision,
                speed_kph   real,
                heading     real,
                received_at timestamptz
            ) ON COMMIT DELETE ROWS""";

    private static final String COPY_SQL =
            "COPY gps_staging (vehicle_id, event_ts, event_id, fleet_id, lat, lon, speed_kph, heading, received_at) "
                    + "FROM STDIN WITH (FORMAT csv)";

    private static final String INSERT_SQL = """
            INSERT INTO gps_events (vehicle_id, event_ts, event_id, fleet_id, lat, lon, speed_kph, heading, received_at)
            SELECT vehicle_id, event_ts, event_id, fleet_id, lat, lon, speed_kph, heading, received_at
            FROM gps_staging
            ON CONFLICT DO NOTHING""";

    private final DataSource dataSource;

    public CopyGpsEventRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public int insertIgnoringDuplicates(List<Ping> pings) throws SQLException {
        if (pings.isEmpty()) {
            return 0;
        }
        String csv = toCsv(pings);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                c.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY_SQL, new StringReader(csv));
                int inserted;
                try (Statement st = c.createStatement()) {
                    inserted = st.executeUpdate(INSERT_SQL);
                }
                c.commit();
                return inserted;
            } catch (SQLException | IOException | RuntimeException e) {
                rollbackQuietly(c, e);
                if (e instanceof SQLException s) {
                    throw s;
                }
                // An IOException from COPY means the stream to the server broke: a connection problem.
                throw new SQLException("COPY failed: " + e.getMessage(), "08006", e);
            }
            // No finally/setAutoCommit(true): HikariCP restores auto-commit when the connection is returned,
            // and calling it on a broken connection would mask the original exception.
        }
    }

    @Override
    public boolean isHealthy() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(2);
            st.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void rollbackQuietly(Connection c, Exception original) {
        try {
            c.rollback();
        } catch (SQLException r) {
            original.addSuppressed(r);
        }
    }

    /** CSV with every text field quoted; a null becomes an empty unquoted field, which COPY reads as NULL. */
    static String toCsv(List<Ping> pings) {
        StringBuilder sb = new StringBuilder(pings.size() * 160);
        for (Ping p : pings) {
            text(sb, p.vehicleId()).append(',');
            if (p.eventTs() != null) {
                sb.append(Instant.ofEpochMilli(p.eventTs()));
            }
            sb.append(',');
            text(sb, p.eventId()).append(',');
            text(sb, p.fleetId()).append(',');
            num(sb, p.lat()).append(',');
            num(sb, p.lon()).append(',');
            num(sb, p.speedKph()).append(',');
            num(sb, p.heading()).append(',');
            sb.append(Instant.ofEpochMilli(p.receivedAt())).append('\n');
        }
        return sb.toString();
    }

    private static StringBuilder text(StringBuilder sb, String s) {
        if (s == null) {
            return sb;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') {
                sb.append('"');
            }
            sb.append(ch);
        }
        return sb.append('"');
    }

    private static StringBuilder num(StringBuilder sb, Double d) {
        return d == null ? sb : sb.append(d.doubleValue());
    }
}
