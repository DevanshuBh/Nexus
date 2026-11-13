package dev.nexus.writer;

import java.sql.SQLException;

/**
 * What a database failure means for the batch that caused it.
 *
 * <ul>
 *   <li>{@link #BAD_DATA} (SQLState class 22, 23): some row is wrong. Retrying the same batch fails the same way,
 *       so split it and isolate the bad rows.</li>
 *   <li>{@link #DB_UNHEALTHY} (08 connection, 53 resources, 57 operator intervention e.g. shutdown):
 *       nothing is wrong with the data. Bisecting would wrongly park good rows in the DLQ; retry the whole batch.</li>
 *   <li>{@link #TRANSIENT} (40 serialization failure / deadlock): retry.</li>
 *   <li>{@link #UNKNOWN}: retry a few times, then stop consuming and alert.</li>
 * </ul>
 */
public enum ErrorClass {
    BAD_DATA, DB_UNHEALTHY, TRANSIENT, UNKNOWN;

    public static ErrorClass of(SQLException e) {
        String state = sqlState(e);
        if (state == null || state.length() < 2) {
            // No SQLState usually means the driver lost the connection before the server answered.
            return DB_UNHEALTHY;
        }
        return switch (state.substring(0, 2)) {
            case "22", "23" -> BAD_DATA;
            case "08", "53", "57" -> DB_UNHEALTHY;
            case "40" -> TRANSIENT;
            default -> UNKNOWN;
        };
    }

    /** The first SQLState found walking the cause chain (pgjdbc sometimes wraps). */
    static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s && s.getSQLState() != null) {
                return s.getSQLState();
            }
        }
        return null;
    }
}
