package org.jlab.epics2web.report;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * How a report shows a WebSocket client. Clients name themselves with the clientName parameter, and
 * the JavaScript client sends its page's address by default. A WEDM screen is shown by its EDL
 * file, from the screen address's edl parameter, and linked to the screen. A client named with
 * another web address is linked to it. The name comes from the client, so only http and https
 * addresses become links.
 */
public final class ClientLabel {

  private final String text;
  private final String title;
  private final String href;

  private ClientLabel(String text, String title, String href) {
    this.text = text;
    this.title = title;
    this.href = href;
  }

  /**
   * Label a client.
   *
   * @param name The name the client gave itself, or null or empty if it gave none
   * @param ip The client's address, which labels clients without a name
   * @return The label
   */
  public static ClientLabel of(String name, String ip) {
    if (name == null || name.isBlank()) {
      return new ClientLabel("Unnamed client at " + ip, null, null);
    }

    URI uri = webAddress(name);

    if (uri == null) {
      return new ClientLabel(name, null, null);
    }

    String edl = wedmScreen(uri);

    if (edl != null) {
      return new ClientLabel(edl, "WEDM screen: " + name, name);
    }

    return new ClientLabel(name, null, name);
  }

  /** The name as an absolute http or https address, or null if it isn't one. */
  private static URI webAddress(String name) {
    try {
      URI uri = new URI(name);
      String scheme = uri.getScheme();
      if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
          && uri.getRawAuthority() != null) {
        return uri;
      }
    } catch (URISyntaxException e) {
      // Not an address
    }
    return null;
  }

  /** The EDL file of a WEDM screen address, or null if it isn't one. */
  private static String wedmScreen(URI uri) {
    String path = uri.getPath();
    String query = uri.getRawQuery();

    if (path == null || !path.endsWith("/wedm/screen") || query == null) {
      return null;
    }

    for (String parameter : query.split("&")) {
      int equals = parameter.indexOf('=');
      if (equals > 0 && "edl".equals(parameter.substring(0, equals))) {
        try {
          String edl = URLDecoder.decode(parameter.substring(equals + 1), StandardCharsets.UTF_8);
          return edl.isBlank() ? null : edl;
        } catch (IllegalArgumentException e) {
          return null; // Malformed escape
        }
      }
    }

    return null;
  }

  /**
   * @return The text to show
   */
  public String getText() {
    return text;
  }

  /**
   * @return More detail, such as for a tooltip, or null
   */
  public String getTitle() {
    return title;
  }

  /**
   * @return The http or https address to link to, or null
   */
  public String getHref() {
    return href;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ClientLabel other
        && text.equals(other.text)
        && Objects.equals(href, other.href);
  }

  @Override
  public int hashCode() {
    return Objects.hash(text, href);
  }

  @Override
  public String toString() {
    return href == null ? text : text + " <" + href + ">";
  }
}
