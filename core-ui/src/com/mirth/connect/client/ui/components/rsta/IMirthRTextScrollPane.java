package com.mirth.connect.client.ui.components.rsta;

import java.awt.dnd.DropTarget;

import javax.swing.border.Border;
import javax.swing.text.JTextComponent;

import com.mirth.connect.model.codetemplates.ContextType;

public interface IMirthRTextScrollPane {
    
    void setContextType(ContextType contextType);
    
    void setSyntaxEditingStyle(String styleKey);
    
    void setBorder(Border border);
    
    void setText(String text);
    
    JTextComponent getTextArea();
    
    void setEditable(boolean b);
    
    void setDropTarget(DropTarget dt);

}
