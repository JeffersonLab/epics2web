package org.jlab.util;

/**
 *
 * @author ryans
 */
public final class Functions {

    private Functions() {
        // cannot instantiate publicly
    }

    public static String contextPrefix() {
        String contextPrefix = System.getenv("CONTEXT_PREFIX");
        
        if(contextPrefix == null) {
            contextPrefix = "";
        }
        
        return contextPrefix;
    }
}
