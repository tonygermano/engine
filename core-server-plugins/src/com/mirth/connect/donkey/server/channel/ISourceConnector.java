package com.mirth.connect.donkey.server.channel;

import com.mirth.connect.donkey.model.message.BatchRawMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.server.message.batch.BatchMessageException;
import com.mirth.connect.donkey.server.message.batch.ResponseHandler;

public interface ISourceConnector extends IConnector {
	
	public String getSourceName();

	public boolean isProcessBatch();
	
	public DispatchResult dispatchRawMessage(RawMessage rawMessage) throws ChannelException;
	
	public Boolean dispatchBatchMessage(BatchRawMessage batchRawMessage, ResponseHandler responseHandler) throws BatchMessageException;
	
	public void finishDispatch(DispatchResult dispatchResult);
	
}
