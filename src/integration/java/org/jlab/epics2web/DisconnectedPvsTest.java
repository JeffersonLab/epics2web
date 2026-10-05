package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Tests the /disconnected-pvs report. Needs build.yaml's 2 second HEALTHCHECK_GRACE_SECONDS. The
 * disconnect test stops and starts the softioc container, so it needs the docker command.
 */
public class DisconnectedPvsTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Rule public Timeout globalTimeout = Timeout.seconds(90);

  @Test
  public void overviewLinksToReport() throws Exception {
    assertTrue(get("overview").body().contains("href=\"disconnected-pvs\""));
  }

  /** A WEDM screen is shown by its EDL file, linked to the screen, with the PVs it's missing. */
  @Test
  public void wedmScreenIsListedWithItsPv() throws Exception {
    String id = UUID.randomUUID().toString();
    String pv = "epics2web:test:report:" + id;
    String edl = "/cs/test/" + id + ".edl";
    String screen = "https://epicsweb.example.org/wedm/screen?edl=" + edl;
    String hostile = "\"><script>alert(1)</script>";

    WebSocket wedm = monitor(pv, screen);
    WebSocket other = monitor(pv, hostile);
    try {
      String row = waitForRow(pv, true);

      assertTrue(row, row.contains("Never connected"));
      assertTrue(row, row.contains("href=\"" + screen + "\""));
      assertTrue(row, row.contains(">" + edl + "</a>"));

      String html = get("disconnected-pvs").body();
      String byClient = section(html, "<table id=\"by-client\">", "</table>");
      assertTrue(byClient, byClient.contains(">" + edl + "</a>"));

      // Names come from clients, so they're escaped and never become links unless http(s)
      assertFalse(html.contains("<script>alert(1)"));
      assertTrue(row, row.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    } finally {
      wedm.abort();
      other.abort();
    }
    waitForRow(pv, false);
  }

  /** A disconnected PV shows the server it was last reached through, which CA no longer knows. */
  @Test
  public void disconnectedPvShowsLastServer() throws Exception {
    assumeTrue("docker command not available", docker("ps") == 0);

    WebSocket socket = monitor("channel1", "DisconnectedPvsTest");
    try {
      Thread.sleep(1_000); // Connected
      assertEquals(0, docker("stop", "-t", "1", "softioc"));

      String row = waitForRow("channel1", true);
      assertTrue(row, row.contains("Disconnected"));

      String via = section(row, "<td class=\"via\">", "</td>");
      assertTrue("Via: " + via, via.matches(".+:5064"));
      assertTrue(row, row.contains(">DisconnectedPvsTest</span>"));
    } finally {
      docker("start", "softioc");
      waitForRow("channel1", false); // Reconnected
      socket.abort();
    }
  }

  private static WebSocket monitor(String pv, String clientName) {
    WebSocket socket =
        HTTP.newWebSocketBuilder()
            .buildAsync(
                URI.create(
                    "ws://localhost:8080/epics2web/monitor?clientName="
                        + URLEncoder.encode(clientName, StandardCharsets.UTF_8)),
                new WebSocket.Listener() {})
            .join();
    socket.sendText("{\"type\": \"monitor\",\"pvs\": [\"" + pv + "\"]}", true).join();
    return socket;
  }

  /** Waits for the report to list, or stop listing, a PV; returns its row if listed. */
  private static String waitForRow(String pv, boolean listed) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get("disconnected-pvs");
      assertEquals(200, response.statusCode());
      String row = row(response.body(), pv);
      if ((row != null) == listed) {
        return row;
      }
      Thread.sleep(200);
    }
    fail(listed ? "Report never listed " + pv : "Report still lists " + pv);
    return null;
  }

  private static String row(String html, String pv) {
    Matcher m =
        Pattern.compile("<tr data-pv=\"" + Pattern.quote(pv) + "\">(.*?)</tr>", Pattern.DOTALL)
            .matcher(html);
    return m.find() ? m.group(1) : null;
  }

  private static String section(String html, String start, String end) {
    int from = html.indexOf(start);
    assertTrue("No " + start, from >= 0);
    int to = html.indexOf(end, from + start.length());
    return html.substring(from + start.length(), to).trim();
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder().uri(URI.create("http://localhost:8080/epics2web/" + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static int docker(String... args) {
    String[] command = new String[args.length + 1];
    command[0] = "docker";
    System.arraycopy(args, 0, command, 1, args.length);
    try {
      return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor();
    } catch (Exception e) {
      return -1;
    }
  }
}
