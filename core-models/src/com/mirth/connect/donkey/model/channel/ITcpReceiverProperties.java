package com.mirth.connect.donkey.model.channel;

import java.util.Set;

public interface ITcpReceiverProperties extends TcpConnectorProperties {
	
    public static final int SAME_CONNECTION = 0;
    public static final int NEW_CONNECTION = 1;
    public static final int NEW_CONNECTION_ON_RECOVERY = 2;
	
	public int getRespondOnNewConnection();
	
	public String getResponseAddress();
	
	public Set<ConnectorPluginProperties> getResponseConnectorPluginProperties();
}
