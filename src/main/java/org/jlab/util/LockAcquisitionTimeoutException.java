package org.jlab.util;

public class LockAcquisitionTimeoutException extends Exception {
    public LockAcquisitionTimeoutException(String message) {
        super(message);
    }
}
