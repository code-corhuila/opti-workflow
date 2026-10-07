package co.edu.corhuila.opti.workflow.application.port.in;

import java.util.List;
import java.util.function.Function;

/** One page of results plus the numbers needed to build {@code meta}. */
public record PageResult<T>(List<T> data, int page, int limit, long total) {

    public int totalPages() {
        return (int) Math.ceil((double) total / limit);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(data.stream().map(mapper).toList(), page, limit, total);
    }
}
