package dev.nexus.writer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorClassTest {

    @ParameterizedTest
    @CsvSource({
            "22021, BAD_DATA",     // invalid byte sequence (NUL in text)
            "22P02, BAD_DATA",     // invalid text representation (bad uuid)
            "23505, BAD_DATA",     // unique violation (should not happen with ON CONFLICT, but is data)
            "23514, BAD_DATA",     // check violation (lat out of range)
            "23502, BAD_DATA",     // not null violation
            "08006, DB_UNHEALTHY", // connection failure
            "08001, DB_UNHEALTHY",
            "53300, DB_UNHEALTHY", // too many connections
            "57P01, DB_UNHEALTHY", // admin shutdown (Postgres restarted mid-COPY)
            "40001, TRANSIENT",    // serialization failure
            "40P01, TRANSIENT",    // deadlock
            "42P01, UNKNOWN",      // undefined table: a bug or a missing migration — stop and alert
            "XX000, UNKNOWN"
    })
    void classifies(String state, ErrorClass expected) {
        assertThat(ErrorClass.of(new SQLException("x", state))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"''"})
    void missingSqlStateMeansConnectionTrouble(String ignored) {
        assertThat(ErrorClass.of(new SQLException("io error"))).isEqualTo(ErrorClass.DB_UNHEALTHY);
    }

    @ParameterizedTest
    @CsvSource({"23514"})
    void findsStateInCauseChain(String state) {
        var wrapped = new SQLException("outer", null, new SQLException("inner", state));
        assertThat(ErrorClass.of(wrapped)).isEqualTo(ErrorClass.BAD_DATA);
    }
}
