package com.mirth.connect.connectors.core.ws;

import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;

import com.mirth.connect.donkey.server.channel.IDestinationConnector;

public interface IWebServiceDispatcher extends IDestinationConnector {

	public RegistryBuilder<ConnectionSocketFactory> getSocketFactoryRegistry();
	
}
