package com.mirth.connect.connectors.core.http;

import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.IDestinationConnector;

public interface IHttpDispatcher extends IDestinationConnector {
    
    public RegistryBuilder<ConnectionSocketFactory> getSocketFactoryRegistry();
<<<<<<< HEAD

    public Response doSend(ConnectorProperties connectorProperties, ConnectorMessage message) throws InterruptedException;
=======
    
    public Response doSend(IHttpDispatcherProperties connectorProperties, ConnectorMessage message);
>>>>>>> 844a048db67bd061e1e3593d6dfe41d4168b0a6d
    
    public void doOnDeploy() throws ConnectorTaskException;
    
    public void doOnUndeploy() throws ConnectorTaskException;

    public void doOnStart() throws ConnectorTaskException;

    public void doOnStop() throws ConnectorTaskException;

    public void doOnHalt() throws ConnectorTaskException;
}
