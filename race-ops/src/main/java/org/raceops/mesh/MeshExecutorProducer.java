package org.raceops.mesh;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * The pool the mesh calls run on. A managed executor keeps the CDI request context, so a
 * call made from a background thread behaves like one made from the snapshot observer.
 */
@ApplicationScoped
public class MeshExecutorProducer {

    private final ManagedExecutor executor;

    @Inject
    public MeshExecutorProducer(ManagedExecutor executor) {
        this.executor = executor;
    }

    @Produces
    @ApplicationScoped
    MeshExecutor meshExecutor() {
        return executor::execute;
    }
}
