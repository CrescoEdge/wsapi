package io.cresco.wsapi.websockets;

import io.cresco.library.plugin.PluginBuilder;
import io.cresco.library.utilities.CLogger;
import io.cresco.wsapi.Plugin;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class AuthFilter implements Filter {

    private CLogger logger;
    private PluginBuilder plugin;
    private String config_service_key;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

        if(plugin == null) {
            if(Plugin.pluginBuilder != null) {
                plugin = Plugin.pluginBuilder;
                logger = plugin.getLogger(AuthFilter.class.getName(), CLogger.Level.Info);
                config_service_key = plugin.getConfig().getStringParam("cresco_service_key");
            }
        }

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {

        if(config_service_key != null) {
            HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
            String incoming_service_key = httpRequest.getHeader("cresco_service_key");

            if(incoming_service_key != null) {
                if (incoming_service_key.equals(config_service_key)) {
                    filterChain.doFilter(servletRequest, servletResponse);
                } else {
                    HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
                    httpServletResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "cresco_service_key mismatch");
                    logger.info("Unauthorized Access: cresco_service_key mismatch");
                }
            } else {
                HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
                httpServletResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Configuration error: Missing [cresco_service_key] request header");
                logger.error("Configuration error: Configuration error: Missing [cresco_service_key] request header");
            }

        } else {
            HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
            httpServletResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Configuration error: Missing server-side [cresco_service_key] configuration");
            logger.error("Configuration error: Missing server-side [cresco_service_key] configuration");
        }

    }

    @Override
    public void destroy() {

    }
}
