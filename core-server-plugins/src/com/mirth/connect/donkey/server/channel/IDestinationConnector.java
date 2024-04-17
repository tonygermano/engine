package com.mirth.connect.donkey.server.channel;

import com.mirth.connect.donkey.model.message.attachment.AttachmentHandlerProvider;

public interface IDestinationConnector extends IConnector {

	public int getPotentialThreadCount();
	
	public String getDestinationName();
	
	public AttachmentHandlerProvider getAttachmentHandlerProvider();
	
}
