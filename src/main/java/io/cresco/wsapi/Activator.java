package io.cresco.wsapi;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    @Override
    public void start(BundleContext context) throws Exception {
        // Route third-party logging (jboss-logging, c3p0/mchange, aries) through SLF4J.
        System.setProperty("org.jboss.logging.provider", "slf4j");
        System.setProperty("com.mchange.v2.log.MLog", "com.mchange.v2.log.FallbackMLog");
        System.setProperty("com.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL", "WARNING");
        System.setProperty("org.apache.aries.logging.provider", "slf4j");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
    }
}
