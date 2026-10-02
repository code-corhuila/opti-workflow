package co.edu.corhuila.opti.workflow.testsupport;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import co.edu.corhuila.opti.workflow.application.port.out.IdGenerator;

/** Predictable identifiers for tests. */
public class SequentialIds implements IdGenerator {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public UUID next() {
        return new UUID(0L, counter.incrementAndGet());
    }
}
