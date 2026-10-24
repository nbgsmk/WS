package nbgsmk.msr;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleAgentTest {

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void startsWorkerAutomaticallyForHeadlessOperation() throws Exception {
		try (ModuleAgent agent = new ModuleAgent("headless", 0)) {
			agent.start();

			HttpResponse<String> response = request(agent, "GET", "/status");

			assertEquals(200, response.statusCode());
			assertTrue(response.body().contains("\"state\":\"running\""));
		}
	}

	@Test
	void canStartWithWorkerStopped() throws Exception {
		try (ModuleAgent agent = new ModuleAgent("manual", 0, false)) {
			agent.start();

			HttpResponse<String> status = request(agent, "GET", "/status");
			assertTrue(status.body().contains("\"state\":\"stopped\""));

			HttpResponse<String> start = request(agent, "POST", "/start");
			assertEquals(202, start.statusCode());
			assertTrue(start.body().contains("\"state\":\"running\""));
		}
	}

	private HttpResponse<String> request(ModuleAgent agent, String method, String path) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + agent.getListenPort() + path));
		HttpRequest request = "GET".equals(method)
				? builder.GET().build()
				: builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
