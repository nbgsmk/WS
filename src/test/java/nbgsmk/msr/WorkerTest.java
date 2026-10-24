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
}
