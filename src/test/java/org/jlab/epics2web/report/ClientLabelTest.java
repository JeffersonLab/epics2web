package org.jlab.epics2web.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ClientLabelTest {

  @Test
  public void wedmScreenIsShownByItsEdlFile() {
    String url = "https://epicsweb.example.org/wedm/screen?edl=/cs/opshome/edm/main.edl";
    ClientLabel label = ClientLabel.of(url, "10.0.0.1");

    assertEquals("/cs/opshome/edm/main.edl", label.getText());
    assertEquals(url, label.getHref());
    assertEquals("WEDM screen: " + url, label.getTitle());
  }

  @Test
  public void wedmScreenBehindAContextPrefixWithMacros() {
    String url =
        "https://epicsweb.example.org/chl/wedm/screen?edl=%2Fcs%2Fchl%2Fa%20b.edl&%24(S)=1&%24(P)=X";
    ClientLabel label = ClientLabel.of(url, "10.0.0.1");

    assertEquals("/cs/chl/a b.edl", label.getText());
    assertEquals(url, label.getHref());
  }

  @Test
  public void otherWebAddressIsLinked() {
    String url = "http://localhost:8080/epics2web/test-camonitor";
    ClientLabel label = ClientLabel.of(url, "10.0.0.1");

    assertEquals(url, label.getText());
    assertEquals(url, label.getHref());
    assertNull(label.getTitle());
  }

  @Test
  public void wedmAddressWithoutAnEdlFileIsAPlainLink() {
    for (String url :
        new String[] {
          "https://example.org/wedm/screen",
          "https://example.org/wedm/screen?edl=",
          "https://example.org/wedm/screen?other=1",
          "https://example.org/wedm/browse?edl=/a.edl"
        }) {
      ClientLabel label = ClientLabel.of(url, "10.0.0.1");
      assertEquals(url, label.getText());
      assertEquals(url, label.getHref());
    }
  }

  @Test
  public void onlyHttpAddressesAreLinked() {
    for (String name :
        new String[] {
          "javascript:alert(1)",
          "JavaScript://example.org/%0Aalert(1)",
          "data:text/html,<script>alert(1)</script>",
          "http:no-authority",
          "https://example.org/wedm/screen?edl=%zz", // Malformed escape
          "/epics2web/relative",
          "\"><script>alert(1)</script>",
          "my python script"
        }) {
      ClientLabel label = ClientLabel.of(name, "10.0.0.1");
      assertEquals(name, label.getText());
      assertNull(name, label.getHref());
    }
  }

  @Test
  public void unnamedClientIsShownByAddress() {
    assertEquals("Unnamed client at 10.0.0.1", ClientLabel.of(null, "10.0.0.1").getText());
    assertEquals("Unnamed client at 10.0.0.1", ClientLabel.of(" ", "10.0.0.1").getText());
    assertNull(ClientLabel.of("", "10.0.0.1").getHref());
  }

  @Test
  public void sameNameIsSameLabel() {
    String url = "https://example.org/wedm/screen?edl=/a.edl";

    assertEquals(ClientLabel.of(url, "10.0.0.1"), ClientLabel.of(url, "10.0.0.2"));
    assertEquals(
        ClientLabel.of(url, "10.0.0.1").hashCode(), ClientLabel.of(url, "10.0.0.2").hashCode());
    assertNotEquals(
        "Other macros",
        ClientLabel.of(url, "10.0.0.1"),
        ClientLabel.of(url + "&%24(P)=X", "10.0.0.1"));
    assertNotEquals(ClientLabel.of(null, "10.0.0.1"), ClientLabel.of(null, "10.0.0.2"));
  }
}
