package co.edu.corhuila.opti.workflow.application.port.in;

/** A bounded page request: {@code page} starts at 1 and {@code limit} is between 1 and 100. */
public record PageQuery(int page, int limit) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public PageQuery {
        if (page < 1 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("page must be >= 1 and limit between 1 and " + MAX_LIMIT);
        }
    }

    public static PageQuery first(int limit) {
        return new PageQuery(1, limit);
    }

    public int offset() {
        return (page - 1) * limit;
    }
}
