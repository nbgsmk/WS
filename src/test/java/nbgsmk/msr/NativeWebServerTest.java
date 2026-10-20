package nbgsmk.msr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class NativeWebServerTest {

	private NativeWebServer proxyServer;
	private int proxyPort;

	@BeforeEach
	void setUp() throws Exception {
		// Use port 0 to bind to any free port for testing
		proxyServer = new NativeWebServer(0, "localhost", 7999);
		proxyServer.start();
		proxyPort = proxyServer.getListenPort();
	}

	@AfterEach
	void tearDown() {
		if (proxyServer != null) {
			proxyServer.close();
		}
	}

	@Test
	void testHttpGetRoot() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + proxyPort + "/"))
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("GET / HTTP/1.1"));
	}

	@Test
	void testHttpGetWithParameters() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + proxyPort + "/?foo=bar&test=123"))
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("/?foo=bar&test=123"));
	}

	@Test
	void testHttpServerSideEvents() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + proxyPort + "/.sse"))
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build();

		HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		assertEquals(200, response.statusCode());
		assertEquals("text/event-stream", response.headers().firstValue("content-type").orElse(""));

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
			String line1 = reader.readLine();
			assertNotNull(line1);
			assertTrue(line1.startsWith("event:"));
		}
	}

	@Test
	void testWebSocketHtmlPage() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + proxyPort + "/.ws"))
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("<title>websocket</title>"));
	}

	@Test
	void testWebSocketEcho() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		CompletableFuture<String> initialBannerFuture = new CompletableFuture<>();
		CompletableFuture<String> echoFuture = new CompletableFuture<>();

		WebSocket.Listener listener = new WebSocket.Listener() {
			private StringBuilder accumulated = new StringBuilder();

			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				accumulated.append(data);
				if (last) {
					String msg = accumulated.toString();
					accumulated = new StringBuilder();
					if (!initialBannerFuture.isDone()) {
						initialBannerFuture.complete(msg);
					} else if (!echoFuture.isDone()) {
						echoFuture.complete(msg);
					}
				}
				webSocket.request(1);
				return WebSocket.Listener.super.onText(webSocket, data, last);
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				initialBannerFuture.completeExceptionally(error);
				echoFuture.completeExceptionally(error);
			}
		};

		WebSocket webSocket = client.newWebSocketBuilder()
				.buildAsync(URI.create("ws://localhost:" + proxyPort + "/.ws"), listener)
				.get(5, TimeUnit.SECONDS);

		// First message from server is the banner
		String banner = initialBannerFuture.get(5, TimeUnit.SECONDS);
		assertTrue(banner.contains("Request served by"));

		// Send message to echo
		String messageToSend = "Hello Proxy WebSocket!";
		webSocket.sendText(messageToSend, true);

		String echoedMessage = echoFuture.get(5, TimeUnit.SECONDS);
		assertEquals(messageToSend, echoedMessage);

		webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
	}

	@Test
	void testHttpPostWithBody() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + proxyPort + "/submit"))
				.timeout(Duration.ofSeconds(5))
				.POST(HttpRequest.BodyPublishers.ofString("hello body payload"))
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("POST /submit HTTP/1.1"));
		assertTrue(response.body().contains("hello body payload"));
	}

	@Test
	void testTargetUnreachableReturns502() throws Exception {
		// Create a proxy with an unreachable port
		try (NativeWebServer badProxy = new NativeWebServer(0, "localhost", 59999)) {
			badProxy.start();
			int badProxyPort = badProxy.getListenPort();

			HttpClient client = HttpClient.newHttpClient();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:" + badProxyPort + "/"))
					.timeout(Duration.ofSeconds(5))
					.GET()
					.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			assertEquals(502, response.statusCode());
			assertTrue(response.body().contains("502 Bad Gateway"));
		}
	}
}
