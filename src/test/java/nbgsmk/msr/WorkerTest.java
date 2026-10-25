package nbgsmk.msr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerTest {

	@Test
	void startsAndStopsDummyWork() throws InterruptedException {
		try (Worker worker = new Worker("test")) {
			assertEquals(Worker.State.STOPPED, worker.state());

			worker.start();
			assertTrue(worker.isRunning());

			worker.stop();
			assertFalse(worker.isRunning());
			assertEquals(Worker.State.STOPPED, worker.state());
		}
	}

	@Test
	void startAndStopAreIdempotent() {
		try (Worker worker = new Worker("test")) {
			worker.start();
			worker.start();
			assertTrue(worker.isRunning());

			worker.stop();
			worker.stop();
			assertEquals(Worker.State.STOPPED, worker.state());
		}
	}

	@Test
	void pausesAndResumesWithoutStoppingTheWorker() {
		try (Worker worker = new Worker("test")) {
			worker.start();
			worker.pause();

			assertTrue(worker.isPaused());
			assertEquals(Worker.State.PAUSED, worker.state());

			worker.resume();
			assertTrue(worker.isRunning());

			worker.stop();
			assertEquals(Worker.State.STOPPED, worker.state());
		}
	}

	@Test
	void stopWorksWhilePaused() {
		try (Worker worker = new Worker("test")) {
			worker.start();
			worker.pause();
			worker.stop();

			assertEquals(Worker.State.STOPPED, worker.state());
		}
	}

	@Test
	void executesInjectedTask() throws InterruptedException {
		var executions = new java.util.concurrent.atomic.AtomicInteger();

		try (Worker worker = new Worker("injected", () -> {
			executions.incrementAndGet();
			try {
				Thread.sleep(Long.MAX_VALUE);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		})) {
			worker.start();

			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
			while (executions.get() == 0 && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}

			assertTrue(executions.get() > 0);
		}
	}

	@Test
	void taskReturningNormallyStopsTheWorker() {
		try (Worker worker = new Worker("finite", () -> { })) {
			worker.start();

			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
			while (worker.state() == Worker.State.RUNNING && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}

			assertEquals(Worker.State.STOPPED, worker.state());
		}
	}
}
