package com.mirth.connect.connectors.http;

import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;

import com.mirth.connect.donkey.server.channel.IDestinationConnector;

public interface IHttpDispatcher extends IDestinationConnector {

    public RegistryBuilder<ConnectionSocketFactory> getSocketFactoryRegistry();
}
