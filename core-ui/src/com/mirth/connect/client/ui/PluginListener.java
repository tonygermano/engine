package com.mirth.connect.client.ui;

import java.awt.event.ActionListener;
import java.awt.Container;

public interface PluginListener extends ActionListener {
	public void addButtonToParent(Container parent, String text, int size);
}
