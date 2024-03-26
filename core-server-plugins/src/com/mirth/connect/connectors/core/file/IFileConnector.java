package com.mirth.connect.connectors.core.file;

import java.net.URI;
import java.net.URISyntaxException;

import com.mirth.connect.connectors.core.file.FileScheme;
import com.mirth.connect.connectors.core.file.SchemeProperties;
import com.mirth.connect.connectors.core.file.filesystems.FileSystemConnection;

public interface IFileConnector {

	public URI getEndpointURI(String host, FileScheme scheme, SchemeProperties schemeProperties, boolean isSecure) throws URISyntaxException;
	
	public void releaseConnection(FileSystemConnection connection, FileSystemConnectionOptions fileSystemOptions) throws Exception;
	
	public void doStop() throws FileConnectorException;
	
	public void disconnect();
	
	public String getPathPart(URI uri);
	
	public FileSystemConnection getConnection(FileSystemConnectionOptions fileSystemOptions) throws Exception;
}
