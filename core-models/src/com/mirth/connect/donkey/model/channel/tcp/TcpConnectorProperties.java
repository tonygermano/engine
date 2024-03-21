package com.mirth.connect.donkey.model.channel.tcp;

public interface TcpConnectorProperties {
	
	public String getRemoteAddress();
	
	public String getRemotePort();
	
	public boolean isOverrideLocalBinding();
	
	public boolean isServerMode();
}
