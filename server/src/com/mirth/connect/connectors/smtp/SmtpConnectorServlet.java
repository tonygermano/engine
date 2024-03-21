/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.connectors.smtp;

import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.connectors.core.smtp.SmtpConnectorServletInterface;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.smtp.SmtpConnectorProperties;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.util.TemplateValueReplacer;
import com.mirth.connect.util.ConnectionTestResponse;

public class SmtpConnectorServlet extends MirthServlet implements SmtpConnectorServletInterface {

    protected static final TemplateValueReplacer replacer = new TemplateValueReplacer();
    protected static final ConfigurationController configurationController = ControllerFactory.getFactory().createConfigurationController();

    public SmtpConnectorServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_POINT);
    }

    @Override
    public ConnectionTestResponse sendTestEmail(String channelId, String channelName, ConnectorProperties properties) {
        try {
            Properties props = new Properties();
            props.put("port", replacer.replaceValues(((SmtpConnectorProperties) properties).getSmtpPort(), channelId, channelName));
            props.put("encryption", ((SmtpConnectorProperties) properties).getEncryption());
            props.put("host", replacer.replaceValues(((SmtpConnectorProperties) properties).getSmtpHost(), channelId, channelName));
            props.put("timeout", ((SmtpConnectorProperties) properties).getTimeout());
            props.put("authentication", String.valueOf(((SmtpConnectorProperties) properties).isAuthentication()));
            props.put("username", replacer.replaceValues(((SmtpConnectorProperties) properties).getUsername(), channelId, channelName));
            props.put("password", replacer.replaceValues(((SmtpConnectorProperties) properties).getPassword(), channelId, channelName));
            props.put("toAddress", replacer.replaceValues(((SmtpConnectorProperties) properties).getTo(), channelId, channelName));
            props.put("fromAddress", replacer.replaceValues(((SmtpConnectorProperties) properties).getFrom(), channelId, channelName));

            return configurationController.sendTestEmail(props);
        } catch (Exception e) {
            throw new MirthApiException(e);
        }
    }
}