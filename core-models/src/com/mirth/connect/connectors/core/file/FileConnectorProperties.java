package com.mirth.connect.connectors.core.file;

public interface FileConnectorProperties {

	public String getHost();
	
	public FileScheme getScheme();
	
	public SchemeProperties getSchemeProperties();
}
