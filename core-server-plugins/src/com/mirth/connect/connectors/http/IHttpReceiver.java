package com.mirth.connect.connectors.http;

import com.mirth.connect.donkey.server.channel.ISourceConnector;

public interface IHttpReceiver extends ISourceConnector {

    public Object getServer();

    public String getHost();

    public int getPort();

    public int getTimeout();
}
