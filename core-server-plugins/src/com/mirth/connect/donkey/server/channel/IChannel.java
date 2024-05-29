package com.mirth.connect.donkey.server.channel;

import java.util.Set;

import com.mirth.connect.donkey.model.channel.DebugOptions;

public interface IChannel {

	public DebugOptions getDebugOptions();
	
	public Set<String> getResourceIds();
	
}
