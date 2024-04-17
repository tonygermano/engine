package com.mirth.connect.donkey.server.channel;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.donkey.model.message.DataType;

public interface IConnector {
	
	public IChannel getChannel();

    public String getChannelId();
    
    public String getChannelName();
    
    public String getConnectorName();
    
    public int getMetaDataId();
    
    public DataType getInboundDataType();
    
    public DeployedState getCurrentState();
    
    public ConnectorProperties getConnectorProperties();
    
}
