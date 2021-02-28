package io.cresco.wsapi;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.plugin.Executor;
import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;

import java.io.File;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.*;

public class PluginExecutor implements Executor {

    private PluginBuilder plugin;
    CLogger logger;

    public PluginExecutor(PluginBuilder pluginBuilder) {
        this.plugin = pluginBuilder;
        logger = plugin.getLogger(PluginExecutor.class.getName(),CLogger.Level.Info);
    }

    @Override
    public MsgEvent executeCONFIG(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeDISCOVER(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeERROR(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeINFO(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeEXEC(MsgEvent ce) {

        switch (ce.getParam("action")) {

            case "repolist":
                return repoList(ce);

            default:
                logger.error("Unknown configtype found {} for {}:", ce.getParam("action"), ce.getMsgType().toString());

        }


        return null;
    }
    @Override
    public MsgEvent executeWATCHDOG(MsgEvent incoming) {
        return null;
    }
    @Override
    public MsgEvent executeKPI(MsgEvent incoming) {
        return null;
    }

    private MsgEvent repoList(MsgEvent msg) {
        //todo fix repo list
        Map<String,List<Map<String,String>>> repoMap = new HashMap<>();
        //repoMap.put("plugins",getPluginInventory(mainPlugin.repoPath));

        List<Map<String,String>> contactMap = getNetworkAddresses();
        repoMap.put("server",contactMap);

        //msg.setCompressedParam("repolist",gson.toJson(repoMap));
        return msg;

    }

    private List<Map<String,String>> getNetworkAddresses() {
        List<Map<String,String>> contactMap = null;
        try {
            contactMap = new ArrayList<>();
            String port = plugin.getConfig().getStringParam("port", "3445");
            String protocol = "http";
            String path = "/repository";

            List<InterfaceAddress> interfaceAddressList = new ArrayList<>();

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.getDisplayName().startsWith("veth") && !networkInterface.isLoopback() && networkInterface.supportsMulticast() && !networkInterface.isPointToPoint() && !networkInterface.isVirtual()) {
                    logger.debug("Found Network Interface [" + networkInterface.getDisplayName() + "] initialized");
                    interfaceAddressList.addAll(networkInterface.getInterfaceAddresses());
                }
            }

            for (InterfaceAddress inaddr : interfaceAddressList) {
                logger.debug("interface addresses " + inaddr);
                Map<String, String> serverMap = new HashMap<>();
                String hostAddress = inaddr.getAddress().getHostAddress();
                if (hostAddress.contains("%")) {
                    String[] remoteScope = hostAddress.split("%");
                    hostAddress = remoteScope[0];
                }

                serverMap.put("protocol", protocol);
                serverMap.put("ip", hostAddress);
                serverMap.put("port", port);
                serverMap.put("path", path);
                contactMap.add(serverMap);

            }


            //put hostname at top of list
            InetAddress addr = InetAddress.getLocalHost();
            String hostAddress = addr.getHostAddress();
            if (hostAddress.contains("%")) {
                String[] remoteScope = hostAddress.split("%");
                hostAddress = remoteScope[0];
            }
            Map<String,String> serverMap = new HashMap<>();
            serverMap.put("protocol", protocol);
            serverMap.put("ip", hostAddress);
            serverMap.put("port", port);
            serverMap.put("path", path);

            contactMap.remove(contactMap.indexOf(serverMap));
            contactMap.add(0,serverMap);

            //Use env var for host with hidden external addresses
            String externalIp = plugin.getConfig().getStringParam("externalip");
            //externalIp = "128.163.202.50";
            if(externalIp != null) {
                Map<String, String> serverMapExternal = new HashMap<>();
                serverMapExternal.put("protocol", protocol);
                serverMapExternal.put("ip", externalIp);
                serverMapExternal.put("port", port);
                serverMapExternal.put("path", path);
                contactMap.add(0,serverMapExternal);
            }
//test
        } catch (Exception ex) {
            logger.error("getNetworkAddresses ", ex.getMessage());
        }


        return contactMap;
    }

    private List<Map<String,String>> getPluginInventory(String repoPath) {
        List<Map<String,String>> pluginFiles = null;
        try
        {
            File folder = new File(repoPath);
            if(folder.exists())
            {
                pluginFiles = new ArrayList<>();
                File[] listOfFiles = folder.listFiles();

                for (int i = 0; i < listOfFiles.length; i++)
                {
                    if (listOfFiles[i].isFile())
                    {
                        try{
                            String jarPath = listOfFiles[i].getAbsolutePath();
                            String jarFileName = listOfFiles[i].getName();
                            String pluginName = plugin.getPluginName(jarPath);
                            String pluginMD5 = plugin.getMD5(jarPath);
                            String pluginVersion = plugin.getPluginVersion(jarPath);
                            //System.out.println(pluginName + " " + jarFileName + " " + pluginVersion + " " + pluginMD5);
                            //pluginFiles.add(listOfFiles[i].getAbsolutePath());
                            Map<String,String> pluginMap = new HashMap<>();
                            pluginMap.put("pluginname",pluginName);
                            pluginMap.put("jarfile",jarFileName);
                            pluginMap.put("md5",pluginMD5);
                            pluginMap.put("version",pluginVersion);
                            pluginFiles.add(pluginMap);
                        } catch(Exception ex) {

                        }

                    }

                }
                if(pluginFiles.isEmpty())
                {
                    pluginFiles = null;
                }
            }
        }
        catch(Exception ex)
        {
            pluginFiles = null;
        }
        return pluginFiles;
    }

}