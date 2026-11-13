package dev.nexus.writer;

import dev.nexus.common.Ping;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Writes a batch, and when the database rejects it because of bad data, isolates the bad rows by bisection.
 *
 * <p>Bisection: on a data error split the batch in half and write each half; recurse into the half that
 * fails. k bad rows in a batch of n cost about {@code 2k·log2(n)} extra round trips, which is why ingress
 * validation matters more: bisection is the safety net, not the filter.
 *
 * <p>Any non-data error aborts the whole write with {@link RetryableBatchException}. Halves already written
 * are committed, which is safe: the retry hits {@code ON CONFLICT DO NOTHING} for them.
 */
public final class BatchWriter {

    public record Isolated<T>(T item, String sqlState, String message) {
    }

    public record Result<T>(int inserted, int attempted, List<Isolated<T>> isolated, int bisections, int maxDepth) {
    }

    /** Thrown when the failure is not the data's fault; the caller retries the whole batch later. */
    public static final class RetryableBatchException extends Exception {
        private final ErrorClass errorClass;

        public RetryableBatchException(ErrorClass errorClass, Exception cause) {
            super(errorClass + ": " + cause.getMessage(), cause);
            this.errorClass = errorClass;
        }

        public ErrorClass errorClass() {
            return errorClass;
        }
    }

    private final GpsEventRepository repository;
    private final int maxDepth;

    public BatchWriter(GpsEventRepository repository, int maxDepth) {
        this.repository = repository;
        this.maxDepth = maxDepth;
    }

    public <T> Result<T> write(List<T> items, Function<T, Ping> toPing) throws RetryableBatchException {
        var state = new State<T>();
        writeRange(items, toPing, 0, state);
        return new Result<>(state.inserted, items.size(), state.isolated, state.bisections, state.deepest);
    }

    private <T> void writeRange(List<T> items, Function<T, Ping> toPing, int depth, State<T> s)
            throws RetryableBatchException {
        if (items.isEmpty()) {
            return;
        }
        s.deepest = Math.max(s.deepest, depth);
        try {
            s.inserted += repository.insertIgnoringDuplicates(items.stream().map(toPing).toList());
        } catch (SQLException e) {
            ErrorClass cls = ErrorClass.of(e);
            if (cls != ErrorClass.BAD_DATA) {
                throw new RetryableBatchException(cls, e);
            }
            if (items.size() == 1 || depth >= maxDepth) {
                // Single bad row found (or depth cap hit: park the remaining group rather than recurse forever).
                for (T item : items) {
                    s.isolated.add(new Isolated<>(item, ErrorClass.sqlState(e), firstLine(e.getMessage())));
                }
                return;
            }
            s.bisections++;
            int mid = items.size() / 2;
            writeRange(items.subList(0, mid), toPing, depth + 1, s);
            writeRange(items.subList(mid, items.size()), toPing, depth + 1, s);
        }
    }

    private static String firstLine(String msg) {
        if (msg == null) {
            return "";
        }
        int nl = msg.indexOf('\n');
        String line = nl < 0 ? msg : msg.substring(0, nl);
        return line.length() > 500 ? line.substring(0, 500) : line;
    }

    private static final class State<T> {
        int inserted;
        int bisections;
        int deepest;
        final List<Isolated<T>> isolated = new ArrayList<>();
    }
}
