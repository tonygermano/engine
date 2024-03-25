package com.mirth.connect.donkey.model.channel.dimse;

public interface DICOMConnectorProperties {

	public String getTls();
	
	public String getTrustStore();
	
	public String getTrustStorePW();
	
	public String getKeyPW();
	
	public String getKeyStore();
	
	public String getKeyStorePW();
	
	public boolean isNoClientAuth();
	
	public boolean isNossl2();
	
}
