package org.jlab.epics2web.epics;

import static org.jlab.epics2web.epics.TestDbrs.doubleDbr;
import static org.jlab.epics2web.epics.TestDbrs.floatDbr;
import static org.junit.Assert.assertEquals;

import gov.aps.jca.dbr.DBR;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import org.junit.Test;

/** Checks how values are written as text and JSON, in particular values that aren't finite. */
public class ChannelManagerValueTest {

  private final ChannelManager manager = new ChannelManager(null, null, null);

  @Test
  public void doubleAsString() {
    assertEquals("1.5", ChannelManager.getDbrValueAsString(doubleDbr(1.5)));
    assertEquals("NaN", ChannelManager.getDbrValueAsString(doubleDbr(Double.NaN)));
    assertEquals(
        "Infinity", ChannelManager.getDbrValueAsString(doubleDbr(Double.POSITIVE_INFINITY)));
    assertEquals(
        "-Infinity", ChannelManager.getDbrValueAsString(doubleDbr(Double.NEGATIVE_INFINITY)));
  }

  @Test
  public void floatAsString() {
    assertEquals("1.5", ChannelManager.getDbrValueAsString(floatDbr(1.5f)));
    assertEquals("NaN", ChannelManager.getDbrValueAsString(floatDbr(Float.NaN)));
    assertEquals("Infinity", ChannelManager.getDbrValueAsString(floatDbr(Float.POSITIVE_INFINITY)));
    assertEquals(
        "-Infinity", ChannelManager.getDbrValueAsString(floatDbr(Float.NEGATIVE_INFINITY)));
  }

  @Test
  public void doubleAsJson() {
    assertEquals("{\"value\":1.5}", json(doubleDbr(1.5)));
    assertEquals("{\"value\":\"NaN\"}", json(doubleDbr(Double.NaN)));
    assertEquals("{\"value\":\"Infinity\"}", json(doubleDbr(Double.POSITIVE_INFINITY)));
    assertEquals("{\"value\":\"-Infinity\"}", json(doubleDbr(Double.NEGATIVE_INFINITY)));
  }

  @Test
  public void floatAsJson() {
    assertEquals("{\"value\":1.5}", json(floatDbr(1.5f)));
    assertEquals("{\"value\":\"NaN\"}", json(floatDbr(Float.NaN)));
    assertEquals("{\"value\":\"Infinity\"}", json(floatDbr(Float.POSITIVE_INFINITY)));
    assertEquals("{\"value\":\"-Infinity\"}", json(floatDbr(Float.NEGATIVE_INFINITY)));
  }

  private String json(DBR dbr) {
    JsonObjectBuilder builder = Json.createObjectBuilder();
    manager.addValueToJSON(builder, dbr);
    return builder.build().toString();
  }
}
