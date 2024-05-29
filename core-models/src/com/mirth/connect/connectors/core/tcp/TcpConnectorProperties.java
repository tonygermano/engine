package com.mirth.connect.connectors.core.tcp;

public interface TcpConnectorProperties {
	
	public String getRemoteAddress();
	
	public String getRemotePort();
	
	public boolean isOverrideLocalBinding();
	
	public boolean isServerMode();
}
