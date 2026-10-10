// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Tony Germano <tony@germano.name>

package com.mirth.connect.client.ui.components;

import java.util.Arrays;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.GapContent;
import javax.swing.text.PlainDocument;
import javax.swing.text.Segment;

/**
 * A {@link PlainDocument} that also treats a lone carriage return as a line
 * break, so CR-delimited content such as HL7 is shown one segment per line.
 * A CR immediately followed by LF is still a single line break. The text
 * itself is stored unchanged. The default tab size is 4.
 */
public class MirthPlainDocument extends PlainDocument {

    /**
     * Constructs a plain text document backed by a gap buffer that recognizes
     * carriage returns as line breaks.
     */
    public MirthPlainDocument() {
        this(new LineBreakContent());
    }

    /**
     * Constructs a plain text document with the given content. Carriage
     * returns are only treated as line breaks if the content was created by
     * the no-argument constructor.
     */
    public MirthPlainDocument(Content c) {
        super(c);
        putProperty(tabSizeAttribute, 4);
    }

    @Override
    protected void insertUpdate(DefaultDocumentEvent chng, AttributeSet attr) {
        if (!(getContent() instanceof LineBreakContent)) {
            super.insertUpdate(chng, attr);
            return;
        }

        /*
         * PlainDocument builds the line map from a single read of the inserted
         * range, splitting after each LF. Presenting lone CRs as LFs for that
         * read gives them the same treatment while producing one element
         * change for the whole insert.
         */
        LineBreakContent content = (LineBreakContent) getContent();
        content.crAsLineFeed = true;
        try {
            super.insertUpdate(chng, attr);
        } finally {
            content.crAsLineFeed = false;
        }
    }

    /**
     * Gap buffer content that can report lone CRs as LFs for a single read.
     */
    private static class LineBreakContent extends GapContent {

        private static final long serialVersionUID = 1L;

        /**
         * When set, the next read reports lone CRs as LFs. Every other read
         * (getText, painting, tokenizing, search, saving) must see the real
         * text, so the translation is limited to the read PlainDocument makes
         * to find line breaks. It is cleared on first use because
         * AbstractDocument.insertUpdate reads the inserted text again
         * afterwards for its bidi and multibyte checks, and those should see
         * the real characters too.
         */
        private transient boolean crAsLineFeed;

        @Override
        public void getChars(int where, int len, Segment chars) throws BadLocationException {
            super.getChars(where, len, chars);
            if (!crAsLineFeed) {
                return;
            }
            crAsLineFeed = false;

            /*
             * The segment may point into the gap buffer, so translate a copy.
             * Pointing the segment at the copy is allowed here: GapContent
             * does the same when the range spans the gap.
             */
            char[] copy = null;
            int end = chars.offset + chars.count;
            for (int i = chars.offset; i < end; i++) {
                if (chars.array[i] == '\r' && (i + 1 == end || chars.array[i + 1] != '\n')) {
                    if (copy == null) {
                        copy = Arrays.copyOfRange(chars.array, chars.offset, end);
                    }
                    copy[i - chars.offset] = '\n';
                }
            }
            if (copy != null) {
                chars.array = copy;
                chars.offset = 0;
            }
        }
    }
}
