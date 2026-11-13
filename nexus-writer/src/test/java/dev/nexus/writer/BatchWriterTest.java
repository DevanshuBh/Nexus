package dev.nexus.writer;

import dev.nexus.common.Ping;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bisection logic against an in-memory repository that rejects batches containing "bad" rows, like Postgres does. */
class BatchWriterTest {

    static final class FakeRepo implements GpsEventRepository {
        final Set<Integer> badIndexes;
        final List<Ping> stored = new ArrayList<>();
        int calls;
        String failAllWith; // SQLState to fail every call with

        FakeRepo(Set<Integer> badIndexes) {
            this.badIndexes = badIndexes;
        }

        @Override
        public int insertIgnoringDuplicates(List<Ping> pings) throws SQLException {
            calls++;
            if (failAllWith != null) {
                throw new SQLException("boom", failAllWith);
            }
            for (Ping p : pings) {
                if (badIndexes.contains(p.heading().intValue())) {
                    throw new SQLException("new row violates check constraint", "23514");
                }
            }
            stored.addAll(pings); // all-or-nothing, like one transaction
            return pings.size();
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }

    /** heading carries the index so the fake can recognise rows. */
    private static List<Ping> batch(int n) {
        return IntStream.range(0, n).mapToObj(i -> new Ping(UUID.randomUUID().toString(), "v" + i, "f01",
                1_000L + i, 1.0, 1.0, 1.0, (double) i, 0)).toList();
    }

    @Test
    void cleanBatchIsOneRoundTrip() throws Exception {
        var repo = new FakeRepo(Set.of());
        var r = new BatchWriter(repo, 16).write(batch(500), Function.identity());
        assertThat(r.inserted()).isEqualTo(500);
        assertThat(r.isolated()).isEmpty();
        assertThat(repo.calls).isEqualTo(1);
    }

    @Test
    void isolatesExactlyTheBadRows() throws Exception {
        var repo = new FakeRepo(Set.of(7, 250, 499));
        var r = new BatchWriter(repo, 16).write(batch(500), Function.identity());
        assertThat(r.inserted()).isEqualTo(497);
        assertThat(repo.stored).hasSize(497);
        assertThat(r.isolated()).extracting(i -> i.item().heading().intValue()).containsExactlyInAnyOrder(7, 250, 499);
        assertThat(r.isolated()).allSatisfy(i -> assertThat(i.sqlState()).isEqualTo("23514"));
        // 3 bad rows in 500: about 2·k·log2(n) ≈ 54 calls, far fewer than 500 single-row inserts
        assertThat(repo.calls).isLessThan(60);
    }

    @Test
    void connectionErrorIsRetriedNotBisected() {
        var repo = new FakeRepo(Set.of());
        repo.failAllWith = "08006";
        assertThatThrownBy(() -> new BatchWriter(repo, 16).write(batch(100), Function.identity()))
                .isInstanceOf(BatchWriter.RetryableBatchException.class)
                .satisfies(e -> assertThat(((BatchWriter.RetryableBatchException) e).errorClass()).isEqualTo(ErrorClass.DB_UNHEALTHY));
        assertThat(repo.calls).isEqualTo(1); // no bisection: nothing parked in the DLQ
    }

    @Test
    void depthCapParksRemainingGroup() throws Exception {
        var repo = new FakeRepo(Set.of(3));
        var r = new BatchWriter(repo, 2).write(batch(16), Function.identity());
        // depth 2 → groups of 4; the group containing row 3 is parked as a whole
        assertThat(r.isolated()).hasSize(4);
        assertThat(r.inserted()).isEqualTo(12);
    }
}
