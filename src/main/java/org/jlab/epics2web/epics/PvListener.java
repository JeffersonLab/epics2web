package org.jlab.epics2web.epics;

import gov.aps.jca.dbr.DBR;
import gov.aps.jca.dbr.DBRType;

/**
 * A contract for classes interested in notifications from an EPICS channel monitor.
 *
 * @author slominskir
 */
public interface PvListener {

  /**
   * Notification of PV metadata sent after registering a PV with a ChannelMonitor.
   *
   * @param pv The PV that was registered
   * @param couldConnect true if the channel connected, false otherwise
   * @param type The EPICS datatype of the channel
   * @param count The EPICS item count
   * @param enumLabels labels for the EPICS enumeration state if datatype is ENUM, null otherwise
   */
  public void notifyPvInfo(
      String pv, boolean couldConnect, DBRType type, Integer count, String[] enumLabels);

  /**
   * Notification of PV value change.
   *
   * @param pv The PV
   * @param dbr The EPICS DataBaseRecord
   */
  public void notifyPvUpdate(String pv, DBR dbr);
}
