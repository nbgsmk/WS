package nbgsmk.msr;

/**
 * A long-lived operation performed by a {@link Worker}.
 *
 * Implementations normally block inside {@link #run()} while listening to a
 * stream or running a continuous processing loop. The worker interrupts the
 * task thread during stop.
 */
@FunctionalInterface
public interface GenericTask {

	void run() throws Exception;

	default void pause() {
	}

	default void resume() {
	}
}
