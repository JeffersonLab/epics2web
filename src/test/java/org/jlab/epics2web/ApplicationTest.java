package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ApplicationTest {

  @Test
  public void parseSecondsUsesValidValue() {
    assertEquals(5, Application.parseSeconds("NAME", "5", 30));
    assertEquals(5, Application.parseSeconds("NAME", " 5 ", 30));
  }

  @Test
  public void parseSecondsUsesDefaultWhenUnset() {
    assertEquals(30, Application.parseSeconds("NAME", null, 30));
    assertEquals(30, Application.parseSeconds("NAME", "", 30));
  }

  @Test
  public void parseSecondsUsesDefaultForInvalidValue() {
    assertEquals(30, Application.parseSeconds("NAME", "abc", 30));
    assertEquals(30, Application.parseSeconds("NAME", "1.5", 30));
    assertEquals(30, Application.parseSeconds("NAME", "0", 30));
    assertEquals(30, Application.parseSeconds("NAME", "-5", 30));
  }
}
