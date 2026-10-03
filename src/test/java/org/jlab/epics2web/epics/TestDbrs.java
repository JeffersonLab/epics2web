package org.jlab.epics2web.epics;

import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;
import gov.aps.jca.dbr.DBR_Double;
import gov.aps.jca.dbr.DBR_Float;
import java.util.Objects;

/**
 * Creates DBRs for tests. Tests create DBRs through this class instead of their constructors: JCA's
 * DBRType constants, such as DBRType.DOUBLE, stay null for the rest of the JVM if a DBR class such
 * as DBR_Double is initialized before DBRType, because the two initializers refer to each other.
 * This class initializes DBRType first.
 */
public final class TestDbrs {

  static {
    Objects.requireNonNull(
        DBRType.DOUBLE, "A DBR class was initialized before DBRType; create DBRs with TestDbrs");
  }

  private TestDbrs() {}

  public static DBR doubleDbr(double value) {
    return new DBR_Double(new double[] {value});
  }

  public static DBR floatDbr(float value) {
    return new DBR_Float(new float[] {value});
  }
}
