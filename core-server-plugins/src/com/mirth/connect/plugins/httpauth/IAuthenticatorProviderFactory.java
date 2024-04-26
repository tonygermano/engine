package com.mirth.connect.plugins.httpauth;

import com.mirth.connect.donkey.model.channel.ConnectorPluginProperties;
import com.mirth.connect.donkey.server.channel.IConnector;

public interface IAuthenticatorProviderFactory {
    
    public AuthenticatorProvider getAuthenticatorProvider(IConnector connector, ConnectorPluginProperties properties) throws Exception;

}
