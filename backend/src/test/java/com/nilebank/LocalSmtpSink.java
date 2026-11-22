package com.nilebank;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Local deterministic SMTP transport, not an external delivery service. */
final class LocalSmtpSink implements AutoCloseable {
  final ServerSocket server;
  final List<String> messages = new CopyOnWriteArrayList<>();
  final Set<String> rejected = ConcurrentHashMap.newKeySet();
  final java.util.concurrent.atomic.AtomicInteger failures =
      new java.util.concurrent.atomic.AtomicInteger();
  final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();

  LocalSmtpSink() {
    try {
      server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Thread.ofPlatform()
        .daemon(true)
        .start(
            () -> {
              while (!server.isClosed())
                try {
                  Socket socket = server.accept();
                  clients.submit(() -> serve(socket));
                } catch (IOException stopped) {
                  if (!server.isClosed()) throw new UncheckedIOException(stopped);
                }
            });
  }

  int port() {
    return server.getLocalPort();
  }

  void serve(Socket socket) {
    try (socket;
        var input =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        var output =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
      output.print("220 localhost test SMTP\r\n");
      output.flush();
      String line;
      boolean data = false;
      StringBuilder body = new StringBuilder();
      while ((line = input.readLine()) != null) {
        if (data) {
          if (line.equals(".")) {
            messages.add(body.toString());
            data = false;
            output.print("250 accepted\r\n");
          } else body.append(line).append('\n');
        } else if (line.startsWith("EHLO") || line.startsWith("HELO"))
          output.print("250 localhost\r\n");
        else if (line.startsWith("RCPT") && rejected.stream().anyMatch(line::contains)) {
          failures.incrementAndGet();
          output.print("550 rejected by test fixture\r\n");
        } else if (line.equals("DATA")) {
          data = true;
          output.print("354 end with dot\r\n");
        } else if (line.equals("QUIT")) {
          output.print("221 bye\r\n");
          output.flush();
          return;
        } else output.print("250 OK\r\n");
        output.flush();
      }
    } catch (IOException ignored) {
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
    clients.close();
  }
}
