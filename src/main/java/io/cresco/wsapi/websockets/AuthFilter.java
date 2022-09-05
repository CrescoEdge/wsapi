package io.cresco.wsapi.websockets;

import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.Plugin;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;

public class AuthFilter implements Filter {

    private CLogger logger;
    private PluginBuilder plugin;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

        if(plugin == null) {
            if(Plugin.pluginBuilder != null) {
                plugin = Plugin.pluginBuilder;
                logger = plugin.getLogger(AuthFilter.class.getName(), CLogger.Level.Info);
            }
        }

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {

        //httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "your message goes here");
        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        String incoming_service_key = httpRequest.getHeader("cresco_service_key");
        String config_service_key = plugin.getConfig().getStringParam("cresco_service_key","abc-8675309");
        if(incoming_service_key.equals(config_service_key)) {
            filterChain.doFilter(servletRequest, servletResponse);
        } else {
            HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
            httpServletResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "cresco service key is incorrect");
        }

    }

    @Override
    public void destroy() {

    }
}
