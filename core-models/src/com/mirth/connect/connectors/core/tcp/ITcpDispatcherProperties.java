package com.mirth.connect.connectors.core.tcp;

public interface ITcpDispatcherProperties extends TcpConnectorProperties {

    public String getLocalAddress();

    public String getLocalPort();
    
	public String getResponseTimeout();
	
}
