package nbgsmk.msr;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A small background worker used by a module. The work is deliberately
 * synthetic for now; the loop is the seam where real processing will be
 * added later.
 */
public final class Worker implements AutoCloseable {

	private final String name;
	private final AtomicBoolean stopRequested = new AtomicBoolean();
	private final AtomicBoolean pauseRequested = new AtomicBoolean();
	private final Object pauseMonitor = new Object();
	private final CopyOnWriteArrayList<Consumer<Status>> statusListeners = new CopyOnWriteArrayList<>();
	private volatile State state = State.STOPPED;
	private volatile Thread thread;
	private volatile long iteration;
	private volatile long lastActivityEpochMillis;
	private volatile String lastError;

	public Worker(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		this.name = name;
	}

	public synchronized void start() {
		if (state == State.RUNNING) {
			return;
		}

		stopRequested.set(false);
		pauseRequested.set(false);
		lastError = null;
		state = State.RUNNING;
		publishStatus();
		thread = Thread.ofVirtual()
				.name("worker-" + name)
				.start(this::runLoop);
	}

	public void pause() {
		if (state == State.RUNNING) {
			pauseRequested.set(true);
			state = State.PAUSED;
			publishStatus();
		}
	}

	public void resume() {
		if (state != State.PAUSED) {
			return;
		}

		pauseRequested.set(false);
		synchronized (pauseMonitor) {
			pauseMonitor.notifyAll();
		}
		state = State.RUNNING;
		publishStatus();
	}

	public synchronized void stop() {
		stopRequested.set(true);
		pauseRequested.set(false);
		synchronized (pauseMonitor) {
			pauseMonitor.notifyAll();
		}
		Thread currentThread = thread;
		if (currentThread != null) {
			currentThread.interrupt();
		}
		if (state != State.FAILED) {
			state = State.STOPPED;
			publishStatus();
		}
	}

	public State state() {
		return state;
	}

	public boolean isRunning() {
		return state == State.RUNNING;
	}

	public boolean isPaused() {
		return state == State.PAUSED;
	}

	public Status status() {
		return new Status(name, state, iteration, lastActivityEpochMillis, lastError);
	}

	public AutoCloseable addStatusListener(Consumer<Status> listener) {
		statusListeners.add(listener);
		listener.accept(status());
		return () -> statusListeners.remove(listener);
	}

	private void runLoop() {
		try {
			while (!stopRequested.get()) {
				waitIfPaused();
				if (stopRequested.get()) {
					break;
				}
				iteration++;
				lastActivityEpochMillis = System.currentTimeMillis();
				System.out.println("[" + name + "] dummy work iteration " + iteration);
				publishStatus();
				Thread.sleep(1_000);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			if (!stopRequested.get()) {
				state = State.FAILED;
				lastError = "worker interrupted unexpectedly";
				publishStatus();
				System.err.println("[" + name + "] worker interrupted unexpectedly");
			}
		} catch (RuntimeException exception) {
			state = State.FAILED;
			lastError = exception.toString();
			publishStatus();
			System.err.println("[" + name + "] worker failed: " + exception.getMessage());
		} finally {
			thread = null;
			if (stopRequested.get() && state != State.FAILED) {
				state = State.STOPPED;
				publishStatus();
			}
		}
	}

	private void publishStatus() {
		Status currentStatus = status();
		for (Consumer<Status> listener : statusListeners) {
			try {
				listener.accept(currentStatus);
			} catch (RuntimeException exception) {
				System.err.println("[" + name + "] status listener failed: " + exception.getMessage());
			}
		}
	}

	private void waitIfPaused() throws InterruptedException {
		synchronized (pauseMonitor) {
			while (pauseRequested.get() && !stopRequested.get()) {
				pauseMonitor.wait();
			}
		}
	}

	@Override
	public void close() {
		stop();
	}

	public enum State {
		RUNNING("running"),
		PAUSED("paused"),
		STOPPED("stopped"),
		FAILED("failed");

		private final String jsonValue;

		State(String jsonValue) {
			this.jsonValue = jsonValue;
		}

		public String jsonValue() {
			return jsonValue;
		}
	}

	public record Status(
			String name,
			State state,
			long iteration,
			long lastActivityEpochMillis,
			String lastError) {
	}
}
