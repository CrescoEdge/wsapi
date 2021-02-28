package io.cresco.wsapi;


import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.util.Dictionary;
import java.util.Hashtable;

public class Activator implements BundleActivator {


    public void start(BundleContext context) throws Exception {

        System.setProperty("org.jboss.logging.provider", "slf4j");
        System.setProperty("com.mchange.v2.log.MLog", "com.mchange.v2.log.FallbackMLog");
        System.setProperty("com.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL", "WARNING");


        System.setProperty("org.apache.aries.logging.provider", "slf4j");

        //java 11
        System.setProperty("javax.xml.bind.JAXBContextFactory","com.sun.xml.bind.v2.ContextFactory");
        System.setProperty("javax.xml.bind.context.factory","com.sun.xml.bind.v2.ContextFactory");



        //com.sun.xml.bind.v2.ContextFactory

        /*
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        */
                    /*
                    java.util.logging.Logger ODBLogger = java.util.logging.Logger.getLogger("com.orientechnologies");
                    ODBLogger.setLevel(Level.ALL);
                    java.util.logging.Logger apacheCommonsLogger = java.util.logging.Logger.getLogger("com.orientechnologies");
                    apacheCommonsLogger.setLevel(Level.ALL);
                    */


        //Delay JAXConnector Until after plugin has loaded for proper sequencing
        //new Thread(new JAXConnectorLoader(context)).start();
    }

    public void stop(BundleContext context) throws Exception {

    }


    private void setHttpConfig(BundleContext context) {
        try {

            ConfigurationAdmin configurationAdmin;

            ServiceReference configurationAdminReference = null;

            configurationAdminReference = context.getServiceReference(ConfigurationAdmin.class.getName());

            if (configurationAdminReference != null) {

                boolean assign = configurationAdminReference.isAssignableTo(context.getBundle(), ConfigurationAdmin.class.getName());

                if (assign) {
                    configurationAdmin = (ConfigurationAdmin) context.getService(configurationAdminReference);

                    Configuration configuration = configurationAdmin.getConfiguration("com.eclipsesource.jaxrs.connector", null);
                    Dictionary props = configuration.getProperties();
                    if (props == null) {
                        props = new Hashtable();
                    }
                    props.put("root", "/");
                    configuration.update(props);


                } else {
                    System.out.println("Could not Assign Configuration Admin!");
                }

            } else {
                System.out.println("Admin Does Not Exist!");
            }
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }

}