package org.jlab.epics2web.report;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names CA servers for reports. CAJ knows a server by its IP address, so the host name comes from a
 * reverse DNS lookup, which is cached: a report names the same few gateways or IOCs over and over,
 * and a lookup can be slow.
 */
public final class ServerNames {

  /** Few servers are expected; the cache is cleared if it grows past this, such as by IP churn. */
  private static final int MAX_CACHED = 10_000;

  private static final Map<InetAddress, String> NAMES = new ConcurrentHashMap<>();

  private ServerNames() {}

  /**
   * Name a server: its host name and port, such as "gateway1.example.org:5064", or its IP address
   * and port if the address has no name.
   *
   * @param server The server's address
   * @return The name
   */
  public static String describe(InetSocketAddress server) {
    InetAddress address = server.getAddress();

    if (address == null) { // Unresolved
      return server.getHostString() + ":" + server.getPort();
    }

    if (NAMES.size() > MAX_CACHED) {
      NAMES.clear();
    }

    // getCanonicalHostName doesn't throw; it answers the IP address if there's no name
    String host = NAMES.computeIfAbsent(address, InetAddress::getCanonicalHostName);

    return host + ":" + server.getPort();
  }
}
