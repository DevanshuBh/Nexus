package dev.nexus.writer;

import dev.nexus.common.Ping;

import java.sql.SQLException;
import java.util.List;

/** All SQL for the write path lives behind this interface. */
public interface GpsEventRepository {

    /**
     * Stores the pings in one transaction, skipping rows that already exist.
     *
     * @return number of rows actually inserted (duplicates are not counted)
     * @throws SQLException with the server's SQLState; nothing from this call is committed
     */
    int insertIgnoringDuplicates(List<Ping> pings) throws SQLException;

    /** Cheap probe used while the consumer is paused. */
    boolean isHealthy();
}
