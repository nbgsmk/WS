package nbgsmk.msr;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ModuleAgent implements Closeable {

	private static final int MAX_HEADER_BYTES = 16 * 1024;
	private static final int MAX_BODY_BYTES = 1024 * 1024;
	private static final String WEB_SOCKET_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private final String instanceName;
	private final int listenPort;
	private final boolean autoStartWorker;
	private final Worker worker;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

	private volatile boolean running;
	private ServerSocket serverSocket;

	public ModuleAgent(String instanceName, int listenPort) {
		this(instanceName, listenPort, true, () -> Thread.sleep(1_000));
	}

	public ModuleAgent(String instanceName, int listenPort, boolean autoStartWorker) {
		this(instanceName, listenPort, autoStartWorker, () -> Thread.sleep(1_000));
	}

	public ModuleAgent(String instanceName, int listenPort, GenericTask task) {
		this(instanceName, listenPort, true, task);
	}

	public ModuleAgent(String instanceName, int listenPort, boolean autoStartWorker, GenericTask task) {
		if (instanceName == null || instanceName.isBlank()) {
			throw new IllegalArgumentException("instanceName must not be blank");
		}
		if (listenPort < 0 || listenPort > 65_535) {
			throw new IllegalArgumentException("listenPort must be between 0 and 65535");
		}

		this.instanceName = instanceName;
		this.listenPort = listenPort;
		this.autoStartWorker = autoStartWorker;
		this.worker = new Worker(instanceName, task);
	}

	public synchronized void start() throws IOException {
		if (running) {
			return;
		}

		serverSocket = new ServerSocket(listenPort);
		running = true;

		System.out.println("============================================================");
		System.out.println(" ModuleAgent instance: " + instanceName);
		System.out.println(" Listening on: http://localhost:" + getListenPort());
		System.out.println(" Status:       GET  /status");
		System.out.println(" Start:        POST /start");
		System.out.println(" Stop:         POST /stop");
		System.out.println(" WebSocket:    ws://localhost:" + getListenPort() + "/.ws");
		System.out.println("============================================================");

		Thread.ofVirtual()
				.name("ModuleAgent-" + instanceName + "-accept-loop")
				.start(this::acceptLoop);

		if (autoStartWorker) {
			worker.start();
		}
	}

	public int getListenPort() {
		ServerSocket socket = serverSocket;
		return socket == null ? listenPort : socket.getLocalPort();
	}

	public String getInstanceName() {
		return instanceName;
	}

	public boolean isRunning() {
		return worker.isRunning();
	}

	private void acceptLoop() {
		while (running && serverSocket != null && !serverSocket.isClosed()) {
			try {
				Socket clientSocket = serverSocket.accept();
				activeSockets.add(clientSocket);
				executor.submit(() -> handleConnection(clientSocket));
			} catch (IOException exception) {
				if (running) {
					System.err.println("[" + instanceName + "] Unable to accept connection: " + exception.getMessage());
				}
			}
		}
	}

	private void handleConnection(Socket socket) {
		try (socket) {
			socket.setTcpNoDelay(true);

			InputStream input = new BufferedInputStream(socket.getInputStream());
			OutputStream output = socket.getOutputStream();
			HttpRequest request = readRequest(input);

			if (request == null) {
				return;
			}

			if (isWebSocketUpgrade(request)) {
				handleWebSocket(request, input, output);
				return;
			}

			handleHttpRequest(request, output);
		} catch (IOException exception) {
			if (running) {
				System.err.println("[" + instanceName + "] Connection failed: " + exception.getMessage());
			}
		} finally {
			activeSockets.remove(socket);
		}
	}

	private void handleHttpRequest(HttpRequest request, OutputStream output) throws IOException {
		String path = request.pathWithoutQuery();

		if ("GET".equals(request.method()) && "/status".equals(path)) {
			writeJsonResponse(output, 200, statusJson());
			return;
		}

		if ("POST".equals(request.method()) && "/start".equals(path)) {
			worker.start();
			writeJsonResponse(output, 202, "{\"state\":\"" + worker.state().jsonValue() + "\"}");
			return;
		}

		if ("POST".equals(request.method()) && "/stop".equals(path)) {
			worker.stop();
			writeJsonResponse(output, 202, "{\"state\":\"" + worker.state().jsonValue() + "\"}");
			return;
		}

		if ("POST".equals(request.method()) && "/pause".equals(path)) {
			worker.pause();
			writeJsonResponse(output, 202, "{\"state\":\"" + worker.state().jsonValue() + "\"}");
			return;
		}

		if ("POST".equals(request.method()) && "/resume".equals(path)) {
			worker.resume();
			writeJsonResponse(output, 202, "{\"state\":\"" + worker.state().jsonValue() + "\"}");
			return;
		}

		if ("GET".equals(request.method()) && "/.sse".equals(path)) {
			writeSseResponse(output);
			return;
		}

		if ("GET".equals(request.method()) && "/.ws".equals(path)) {
			writeWebSocketHtmlPage(output);
			return;
		}

		writeEchoResponse(request, output);
	}

	private void writeJsonResponse(OutputStream output, int status, String json) throws IOException {
		writeResponse(output, status, "OK", "application/json; charset=utf-8",
				json.getBytes(StandardCharsets.UTF_8));
	}

	private void writeSseResponse(OutputStream output) throws IOException {
		String headers = "HTTP/1.1 200 OK\r\n"
				+ "Content-Type: text/event-stream\r\n"
				+ "Cache-Control: no-cache\r\n"
				+ "Connection: keep-alive\r\n"
				+ "Transfer-Encoding: chunked\r\n"
				+ "\r\n";

		output.write(headers.getBytes(StandardCharsets.US_ASCII));
		output.flush();

		LinkedBlockingQueue<Worker.Status> updates = new LinkedBlockingQueue<>();
		AutoCloseable registration = worker.addStatusListener(updates::offer);
		try {
			while (running) {
				Worker.Status status = updates.poll(15, TimeUnit.SECONDS);
				if (status == null) {
					writeSseComment(output, "heartbeat");
				} else {
					writeSseEvent(output, "status", statusJson(status));
				}
				output.flush();
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} finally {
			try {
				registration.close();
			} catch (Exception ignored) {
			}
		}
	}

	private String statusJson() {
		return statusJson(worker.status());
	}

	private String statusJson(Worker.Status status) {
		return "{\"name\":\"" + escapeJson(status.name())
				+ "\",\"state\":\"" + status.state().jsonValue()
				+ "\",\"iteration\":" + status.iteration()
				+ ",\"lastActivityEpochMillis\":" + status.lastActivityEpochMillis()
				+ ",\"lastError\":" + (status.lastError() == null ? "null" : "\"" + escapeJson(status.lastError()) + "\"")
				+ "}";
	}

	private void writeSseEvent(OutputStream output, String eventName, String data) throws IOException {
		writeSseChunk(output, "event: " + eventName + "\ndata: " + data + "\n\n");
	}

	private void writeSseComment(OutputStream output, String comment) throws IOException {
		writeSseChunk(output, ": " + comment + "\n\n");
	}

	private void writeSseChunk(OutputStream output, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		output.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
		output.write(bytes);
		output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
	}

	private static String escapeJson(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private void writeWebSocketHtmlPage(OutputStream output) throws IOException {
		String html = """
				<!doctype html>
				<html lang="en">
				<head>
				  <meta charset="utf-8">
				  <title>websocket</title>
				</head>
				<body>
				  <h1>WebSocket: %s</h1>
				  <pre id="messages"></pre>
				  <script>
				    const messages = document.getElementById("messages");
				    const socket = new WebSocket(
				      (location.protocol === "https:" ? "wss://" : "ws://")
				      + location.host + "/.ws"
				    );
				    socket.onmessage = event => messages.textContent += event.data + "\\n";
				    socket.onclose = () => messages.textContent += "connection closed\\n";
				  </script>
				</body>
				</html>
				""".formatted(escapeHtml(instanceName));

		writeResponse(output, 200, "OK", "text/html; charset=utf-8",
				html.getBytes(StandardCharsets.UTF_8));
	}

	private void writeEchoResponse(HttpRequest request, OutputStream output) throws IOException {
		StringBuilder response = new StringBuilder();
		response.append("Request served by ").append(instanceName).append('\n');
		response.append(request.requestLine()).append('\n');

		for (Map.Entry<String, String> header : request.headers().entrySet()) {
			response.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
		}

		if (request.body().length > 0) {
			response.append('\n');
			response.append(new String(request.body(), StandardCharsets.UTF_8));
		}

		writeResponse(output, 200, "OK", "text/plain; charset=utf-8",
				response.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void writeResponse(
			OutputStream output,
			int status,
			String reason,
			String contentType,
			byte[] body
	) throws IOException {
		String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
				+ "Content-Type: " + contentType + "\r\n"
				+ "Content-Length: " + body.length + "\r\n"
				+ "Connection: close\r\n"
				+ "\r\n";

		output.write(headers.getBytes(StandardCharsets.US_ASCII));
		output.write(body);
		output.flush();
	}

	private boolean isWebSocketUpgrade(HttpRequest request) {
		return "GET".equals(request.method())
				&& "/.ws".equals(request.pathWithoutQuery())
				&& "websocket".equalsIgnoreCase(request.headers().get("upgrade"))
				&& request.headers().getOrDefault("connection", "")
				.toLowerCase()
				.contains("upgrade")
				&& request.headers().containsKey("sec-websocket-key");
	}

	private void handleWebSocket( HttpRequest request, InputStream input, OutputStream output) throws IOException {
		String key = request.headers().get("sec-websocket-key");
		String accept = createWebSocketAccept(key);

		String response = "HTTP/1.1 101 Switching Protocols\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Accept: " + accept + "\r\n"
				+ "\r\n";

		output.write(response.getBytes(StandardCharsets.US_ASCII));
		output.flush();

		writeWebSocketText(output, "Request served by " + instanceName);

		while (running) {
			WebSocketFrame frame = readWebSocketFrame(input);
			if (frame == null) {
				return;
			}

			switch (frame.opcode()) {
				case 0x1 -> writeWebSocketText(output, new String(frame.payload(), StandardCharsets.UTF_8));
				case 0x8 -> {
					writeWebSocketFrame(output, 0x8, frame.payload());
					return;
				}
				case 0x9 -> writeWebSocketFrame(output, 0xA, frame.payload());
				default -> {
					// Text WebSocket clients are supported. Other frame types are ignored.
				}
			}
		}
	}

	private static HttpRequest readRequest(InputStream input) throws IOException {
		String requestLine = readLine(input);
		if (requestLine == null || requestLine.isBlank()) {
			return null;
		}

		String[] requestParts = requestLine.split(" ", 3);
		if (requestParts.length != 3) {
			throw new IOException("Invalid HTTP request line");
		}

		Map<String, String> headers = new LinkedHashMap<>();
		int headerBytes = requestLine.length();

		while (true) {
			String line = readLine(input);
			if (line == null) {
				throw new IOException("Unexpected end of request headers");
			}
			if (line.isEmpty()) {
				break;
			}

			headerBytes += line.length();
			if (headerBytes > MAX_HEADER_BYTES) {
				throw new IOException("HTTP headers exceed " + MAX_HEADER_BYTES + " bytes");
			}

			int separator = line.indexOf(':');
			if (separator <= 0) {
				throw new IOException("Invalid HTTP header");
			}

			String name = line.substring(0, separator).trim().toLowerCase();
			String value = line.substring(separator + 1).trim();
			headers.put(name, value);
		}

		int contentLength = parseContentLength(headers.get("content-length"));
		byte[] body = input.readNBytes(contentLength);
		if (body.length != contentLength) {
			throw new IOException("Unexpected end of HTTP request body");
		}

		return new HttpRequest(requestParts[0], requestParts[1], requestLine, headers, body);
	}

	private static int parseContentLength(String value) throws IOException {
		if (value == null || value.isBlank()) {
			return 0;
		}

		try {
			int contentLength = Integer.parseInt(value);
			if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
				throw new IOException("Invalid Content-Length");
			}
			return contentLength;
		} catch (NumberFormatException exception) {
			throw new IOException("Invalid Content-Length", exception);
		}
	}

	private static String readLine(InputStream input) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();

		while (line.size() <= MAX_HEADER_BYTES) {
			int next = input.read();
			if (next == -1) {
				// line.isEmpty() to je bilo prvo
				// return line.isEmpty() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
				return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
			}
			if (next == '\n') {
				byte[] bytes = line.toByteArray();
				int length = bytes.length;
				if (length > 0 && bytes[length - 1] == '\r') {
					length--;
				}
				return new String(bytes, 0, length, StandardCharsets.US_ASCII);
			}
			line.write(next);
		}

		throw new IOException("HTTP line exceeds " + MAX_HEADER_BYTES + " bytes");
	}

	private static WebSocketFrame readWebSocketFrame(InputStream input) throws IOException {
		int first = input.read();
		if (first == -1) {
			return null;
		}

		int second = input.read();
		if (second == -1) {
			throw new IOException("Incomplete WebSocket frame");
		}

		int opcode = first & 0x0F;
		boolean masked = (second & 0x80) != 0;
		long payloadLength = second & 0x7F;

		if (payloadLength == 126) {
			payloadLength = readUnsignedShort(input);
		} else if (payloadLength == 127) {
			payloadLength = readLong(input);
		}

		if (!masked) {
			throw new IOException("Client WebSocket frames must be masked");
		}
		if (payloadLength < 0 || payloadLength > MAX_BODY_BYTES) {
			throw new IOException("WebSocket payload is too large");
		}

		byte[] mask = input.readNBytes(4);
		if (mask.length != 4) {
			throw new IOException("Incomplete WebSocket mask");
		}

		byte[] payload = input.readNBytes((int) payloadLength);
		if (payload.length != payloadLength) {
			throw new IOException("Incomplete WebSocket payload");
		}

		for (int index = 0; index < payload.length; index++) {
			payload[index] ^= mask[index % 4];
		}

		return new WebSocketFrame(opcode, payload);
	}

	private static void writeWebSocketText(OutputStream output, String text) throws IOException {
		writeWebSocketFrame(output, 0x1, text.getBytes(StandardCharsets.UTF_8));
	}

	private static void writeWebSocketFrame(
			OutputStream output,
			int opcode,
			byte[] payload
	) throws IOException {
		output.write(0x80 | opcode);

		if (payload.length < 126) {
			output.write(payload.length);
		} else if (payload.length <= 65_535) {
			output.write(126);
			output.write((payload.length >>> 8) & 0xFF);
			output.write(payload.length & 0xFF);
		} else {
			output.write(127);
			for (int shift = 56; shift >= 0; shift -= 8) {
				output.write((payload.length >>> shift) & 0xFF);
			}
		}

		output.write(payload);
		output.flush();
	}

	private static int readUnsignedShort(InputStream input) throws IOException {
		int high = input.read();
		int low = input.read();
		if (high == -1 || low == -1) {
			throw new IOException("Incomplete WebSocket payload length");
		}
		return (high << 8) | low;
	}

	private static long readLong(InputStream input) throws IOException {
		long value = 0;

		for (int index = 0; index < 8; index++) {
			int next = input.read();
			if (next == -1) {
				throw new IOException("Incomplete WebSocket payload length");
			}
			value = (value << 8) | next;
		}

		return value;
	}

	private static String createWebSocketAccept(String key) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] hash = digest.digest(
					(key + WEB_SOCKET_MAGIC).getBytes(StandardCharsets.US_ASCII)
			);
			return Base64.getEncoder().encodeToString(hash);
		} catch (NoSuchAlgorithmException exception) {
			throw new IOException("SHA-1 is unavailable", exception);
		}
	}

	private static String escapeHtml(String text) {
		return text
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}

	@Override
	public synchronized void close() {
		running = false;
		worker.close();

		for (Socket socket : activeSockets) {
			closeQuietly(socket);
		}
		activeSockets.clear();

		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (IOException ignored) {
			}
		}

		executor.shutdownNow();
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}

	private record HttpRequest(
			String method,
			String target,
			String requestLine,
			Map<String, String> headers,
			byte[] body
	) {
		private String pathWithoutQuery() {
			int queryStart = target.indexOf('?');
			return queryStart < 0 ? target : target.substring(0, queryStart);
		}
	}

	private record WebSocketFrame(int opcode, byte[] payload) {
	}
}
