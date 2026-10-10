// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Tony Germano <tony@germano.name>

package com.mirth.connect.client.ui.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;
import javax.swing.undo.UndoManager;

import org.junit.Test;

public class MirthPlainDocumentTest {

    @Test
    public void testTabSize() {
        assertEquals(4, new MirthPlainDocument().getProperty(PlainDocument.tabSizeAttribute));
    }

    @Test
    public void testLoneCarriageReturn() throws Exception {
        assertLines(docWith("a\rb"), "a\r", "b");
    }

    @Test
    public void testLineFeed() throws Exception {
        assertLines(docWith("a\nb"), "a\n", "b");
    }

    @Test
    public void testCrLf() throws Exception {
        assertLines(docWith("a\r\nb"), "a\r\n", "b");
    }

    @Test
    public void testLfCr() throws Exception {
        assertLines(docWith("a\n\rb"), "a\n", "\r", "b");
    }

    @Test
    public void testMixed() throws Exception {
        assertLines(docWith("\r\r\n"), "\r", "\r\n", "");
    }

    @Test
    public void testTextIsUnchanged() throws Exception {
        MirthPlainDocument doc = docWith("a\rb\r\nc\n");
        assertEquals("a\rb\r\nc\n", doc.getText(0, doc.getLength()));
    }

    @Test
    public void testHl7() throws Exception {
        MirthPlainDocument doc = docWith("MSH|^~\\&|A\rPID|1\rPV1|1\r");
        assertLines(doc, "MSH|^~\\&|A\r", "PID|1\r", "PV1|1\r", "");
    }

    @Test
    public void testLineFeedAfterCarriageReturnJoins() throws Exception {
        MirthPlainDocument doc = docWith("a\rb");
        DocumentEvent.ElementChange change = captureInsert(doc, 2, "\n");
        assertLines(doc, "a\r\n", "b");
        assertEquals(1, change.getChildrenRemoved().length);
        assertEquals(1, change.getChildrenAdded().length);
    }

    @Test
    public void testInsertBetweenCrLfSplits() throws Exception {
        MirthPlainDocument doc = docWith("a\r\nb");
        DocumentEvent.ElementChange change = captureInsert(doc, 2, "x");
        assertLines(doc, "a\r", "x\n", "b");
        assertEquals(0, change.getIndex());
        assertEquals(1, change.getChildrenRemoved().length);
        assertEquals(2, change.getChildrenAdded().length);
    }

    @Test
    public void testInsertMultipleLinesSingleChange() throws Exception {
        MirthPlainDocument doc = docWith("ab");
        DocumentEvent.ElementChange change = captureInsert(doc, 1, "1\r2\r\n3\n4");
        assertLines(doc, "a1\r", "2\r\n", "3\n", "4b");
        assertEquals(0, change.getIndex());
        assertEquals(1, change.getChildrenRemoved().length);
        assertEquals(4, change.getChildrenAdded().length);
    }

    @Test
    public void testRemoveAcrossLines() throws Exception {
        MirthPlainDocument doc = docWith("a\rb\rc");
        doc.remove(1, 2);
        assertLines(doc, "a\r", "c");
    }

    @Test
    public void testUndoRedo() throws Exception {
        MirthPlainDocument doc = new MirthPlainDocument();
        UndoManager undo = new UndoManager();
        doc.addUndoableEditListener(undo);
        doc.insertString(0, "a\rb", null);
        undo.undo();
        assertLines(doc, "");
        undo.redo();
        assertLines(doc, "a\r", "b");
    }

    private static MirthPlainDocument docWith(String text) throws BadLocationException {
        MirthPlainDocument doc = new MirthPlainDocument();
        doc.insertString(0, text, null);
        return doc;
    }

    private static DocumentEvent.ElementChange captureInsert(MirthPlainDocument doc, int offset, String text) throws BadLocationException {
        List<DocumentEvent.ElementChange> changes = new ArrayList<>();
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changes.add(e.getChange(doc.getDefaultRootElement()));
            }

            @Override
            public void removeUpdate(DocumentEvent e) {}

            @Override
            public void changedUpdate(DocumentEvent e) {}
        };
        doc.addDocumentListener(listener);
        doc.insertString(offset, text, null);
        doc.removeDocumentListener(listener);
        assertEquals(1, changes.size());
        assertNotNull(changes.get(0));
        return changes.get(0);
    }

    private static void assertLines(MirthPlainDocument doc, String... expected) throws BadLocationException {
        Element root = doc.getDefaultRootElement();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < root.getElementCount(); i++) {
            Element line = root.getElement(i);
            int end = Math.min(line.getEndOffset(), doc.getLength());
            lines.add(doc.getText(line.getStartOffset(), end - line.getStartOffset()));
        }
        assertEquals(Arrays.asList(expected), lines);
    }
}
