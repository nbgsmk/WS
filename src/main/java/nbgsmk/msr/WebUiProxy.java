package nbgsmk.msr;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebUiProxy implements Closeable {

	private final int listenPort;
	private final String targetHost;
	private final int targetPort;
	private ServerSocket serverSocket;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private volatile boolean running = false;

	public WebUiProxy(int listenPort, String targetHost, int targetPort) {
		this.listenPort = listenPort;
		this.targetHost = targetHost;
		this.targetPort = targetPort;
	}

	public synchronized void start() throws IOException {
		if (running) {
			return;
		}
		serverSocket = new ServerSocket(listenPort);
		running = true;

		System.out.println("=================================================================");
		System.out.println(" Proxy Server running on: http://localhost:" + getListenPort());
		System.out.println(" Target Container:        http://" + targetHost + ":" + targetPort);
		System.out.println("-----------------------------------------------------------------");
		System.out.println(" Test Endpoints:");
		System.out.println(" 1. HTTP GET (echo):      http://localhost:" + getListenPort() + "/");
		System.out.println(" 2. HTTP GET with params: http://localhost:" + getListenPort() + "/?foo=bar");
		System.out.println(" 3. Server-Sent Events:   http://localhost:" + getListenPort() + "/.sse");
		System.out.println(" 4. WebSocket HTML Page:  http://localhost:" + getListenPort() + "/.ws");
		System.out.println(" 5. WebSocket Endpoint:   ws://localhost:" + getListenPort() + "/.ws");
		System.out.println("=================================================================");

		Thread.ofVirtual().name("proxy-accept-loop").start(this::acceptLoop);
	}

	private void acceptLoop() {
		while (running && serverSocket != null && !serverSocket.isClosed()) {
			try {
				Socket clientSocket = serverSocket.accept();
				executor.submit(() -> handleConnection(clientSocket));
			} catch (IOException e) {
				if (!running || serverSocket.isClosed()) {
					break;
				}
				System.err.println("[Proxy] Error accepting connection: " + e.getMessage());
			}
		}
	}

	private void handleConnection(Socket clientSocket) {
		Socket targetSocket = null;
		try {
			clientSocket.setTcpNoDelay(true);
			targetSocket = new Socket(targetHost, targetPort);
			targetSocket.setTcpNoDelay(true);

			final Socket finalTargetSocket = targetSocket;

			Thread clientToTarget = Thread.ofVirtual().start(() -> pipeStreams(clientSocket, finalTargetSocket, true));
			Thread targetToClient = Thread.ofVirtual().start(() -> pipeStreams(finalTargetSocket, clientSocket, false));

			clientToTarget.join();
			targetToClient.join();
		} catch (IOException e) {
			try {
				if (!clientSocket.isClosed()) {
					OutputStream out = clientSocket.getOutputStream();
					String errorMsg = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\nContent-Length: 49\r\n\r\n502 Bad Gateway: Target container is unreachable.\n";
					out.write(errorMsg.getBytes(StandardCharsets.UTF_8));
					out.flush();
				}
			} catch (IOException ignored) {
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			closeQuietly(clientSocket);
			if (targetSocket != null) {
				closeQuietly(targetSocket);
			}
		}
	}

	private void pipeStreams(Socket source, Socket destination, boolean clientToTarget) {
		byte[] buffer = new byte[8192];
		try {
			InputStream in = source.getInputStream();
			OutputStream out = destination.getOutputStream();
			int bytesRead;
			while ((bytesRead = in.read(buffer)) != -1) {
				out.write(buffer, 0, bytesRead);
				out.flush();
			}
			if (!destination.isClosed()) {
				try {
					destination.shutdownOutput();
				} catch (IOException ignored) {
				}
			}
		} catch (IOException ignored) {
		} finally {
			if (!destination.isClosed() && !clientToTarget) {
				closeQuietly(destination);
			}
		}
	}

	private static void closeQuietly(Socket socket) {
		try {
			if (socket != null && !socket.isClosed()) {
				socket.close();
			}
		} catch (IOException ignored) {
		}
	}

	@Override
	public synchronized void close() {
		running = false;
		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (IOException ignored) {
			}
		}
		executor.shutdownNow();
	}

	public int getListenPort() {
		return serverSocket != null ? serverSocket.getLocalPort() : listenPort;
	}

public static void main(String[] args) throws IOException {
		int listenPort = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
		String targetHost = args.length > 1 ? args[1] : "localhost";
		int targetPort = args.length > 2 ? Integer.parseInt(args[2]) : 7999;

		WebUiProxy server = new WebUiProxy(listenPort, targetHost, targetPort);
		server.start();

		BGS bg1 = new BGS("BG1", 0);
		BGS bg2 = new BGS("BG2", 0);

		bg1.start();
		bg2.start();

		System.out.println(bg1.getListenPort());
		System.out.println(bg2.getListenPort());

		Runtime.getRuntime().addShutdownHook(new Thread(server::close));

		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}




