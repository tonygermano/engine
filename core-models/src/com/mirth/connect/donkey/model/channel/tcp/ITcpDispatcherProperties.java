package com.mirth.connect.donkey.model.channel.tcp;

public interface ITcpDispatcherProperties extends TcpConnectorProperties {

    public String getLocalAddress();

    public String getLocalPort();
    
	public String getResponseTimeout();
	
}
