package com.mirth.connect.donkey.server.channel;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;

public interface IConnector {

    public String getChannelId();
    
    public String getChannelName();
    
    public String getConnectorName();
    
    public int getMetaDataId();
    
    public ConnectorProperties getConnectorProperties();
}
