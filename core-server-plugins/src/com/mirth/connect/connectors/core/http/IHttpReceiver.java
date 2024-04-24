package com.mirth.connect.connectors.core.http;

import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.channel.ISourceConnector;

public interface IHttpReceiver extends ISourceConnector {

    public Object getServer();

    public String getHost();

    public int getPort();

    public int getTimeout();
    
	public void doHandleRecoveredResponse(DispatchResult dispatchResult);

    public void doOnDeploy() throws ConnectorTaskException;
    
    public void doOnUndeploy() throws ConnectorTaskException;

    public void doOnStart() throws ConnectorTaskException;

    public void doOnStop() throws ConnectorTaskException;

    public void doOnHalt() throws ConnectorTaskException;
}
