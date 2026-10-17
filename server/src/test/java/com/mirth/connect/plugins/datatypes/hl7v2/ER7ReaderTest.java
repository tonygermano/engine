// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Nico Piel <nico.piel@hotmail.de>
// SPDX-FileCopyrightText: 2026 Tony Germano <tony@germano.name>

package com.mirth.connect.plugins.datatypes.hl7v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.mirth.connect.model.converters.XMLPrettyPrinter;
import com.mirth.connect.model.datatype.SerializerProperties;

/**
 * Tests for {@link ER7Reader}, the non-strict ER7 to XML path of {@link ER7Serializer#toXML(String)}.
 * Besides the targeted XPath checks, the tests after them pin the exact SAX events for whole
 * messages, quirks included, so any change to them is deliberate.
 * Those events are recorded as compact XML with only {@code &} and {@code <} escaped in text, and
 * expected values are written one segment per line and compacted before comparison.
 */
public class ER7ReaderTest {

    private static Document parse(String message, boolean handleRepetitions, boolean handleSubcomponents) throws Exception {
        ER7Reader reader = new ER7Reader(handleRepetitions, handleSubcomponents, "\r");
        StringWriter writer = new StringWriter();
        XMLPrettyPrinter printer = new XMLPrettyPrinter(writer);
        printer.setEncodeEntities(true);
        reader.setContentHandler(printer);
        reader.parse(new InputSource(new StringReader(message)));
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(writer.toString())));
    }

    private static Document parse(String message) throws Exception {
        return parse(message, false, false);
    }

    private static String text(Document doc, String xpath) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(xpath, doc);
    }

    private static int count(Document doc, String xpath) throws Exception {
        return ((NodeList) XPathFactory.newInstance().newXPath().evaluate(xpath, doc, XPathConstants.NODESET)).getLength();
    }

    /** Records SAX events as compact XML. */
    private static class RecordingHandler extends DefaultHandler {
        private final StringBuilder output = new StringBuilder();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            output.append('<').append(localName).append('>');
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            output.append("</").append(localName).append('>');
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            output.append(new String(ch, start, length).replace("&", "&amp;").replace("<", "&lt;"));
        }
    }

    private static String read(String er7) throws Exception {
        return read(er7, true, true, "\r");
    }

    private static String read(String er7, boolean handleRepetitions, boolean handleSubcomponents, String segmentDelimiter) throws Exception {
        ER7Reader reader = new ER7Reader(handleRepetitions, handleSubcomponents, segmentDelimiter);
        RecordingHandler handler = new RecordingHandler();
        reader.setContentHandler(handler);
        reader.parse(new InputSource(new StringReader(er7)));
        return handler.output.toString();
    }

    /** Removes the line breaks and indentation used to lay out expected values. */
    private static String compact(String expected) {
        return expected.replaceAll("\\n\\s*", "");
    }

    @Test
    public void testEmptyFields() throws Exception {
        Document doc = parse("MSH|^~\\&\rPID|||X|");

        assertEquals(1, count(doc, "/HL7Message/PID/PID.1"));
        assertEquals(0, count(doc, "/HL7Message/PID/PID.1/*"));
        assertEquals(1, count(doc, "/HL7Message/PID/PID.2"));
        assertEquals("X", text(doc, "/HL7Message/PID/PID.3/PID.3.1"));
        // A trailing field separator still produces the final, empty field
        assertEquals(1, count(doc, "/HL7Message/PID/PID.4"));
        assertEquals(0, count(doc, "/HL7Message/PID/PID.4/*"));
    }

    @Test
    public void testEmptyComponents() throws Exception {
        Document doc = parse("MSH|^~\\&\rPID|^A^");

        assertEquals("", text(doc, "/HL7Message/PID/PID.1/PID.1.1"));
        assertEquals("A", text(doc, "/HL7Message/PID/PID.1/PID.1.2"));
        assertEquals(1, count(doc, "/HL7Message/PID/PID.1/PID.1.3"));
        assertEquals("", text(doc, "/HL7Message/PID/PID.1/PID.1.3"));
    }

    @Test
    public void testRepetitionsIgnoredByDefault() throws Exception {
        Document doc = parse("MSH|^~\\&\rPID|A~B");

        assertEquals(1, count(doc, "/HL7Message/PID/PID.1"));
        assertEquals("A~B", text(doc, "/HL7Message/PID/PID.1/PID.1.1"));
    }

    @Test
    public void testHandleRepetitions() throws Exception {
        Document doc = parse("MSH|^~\\&\rPID|A~~B^C~", true, false);

        assertEquals(4, count(doc, "/HL7Message/PID/PID.1"));
        assertEquals("A", text(doc, "/HL7Message/PID/PID.1[1]/PID.1.1"));
        assertEquals(0, count(doc, "/HL7Message/PID/PID.1[2]/*"));
        assertEquals("B", text(doc, "/HL7Message/PID/PID.1[3]/PID.1.1"));
        assertEquals("C", text(doc, "/HL7Message/PID/PID.1[3]/PID.1.2"));
        assertEquals(0, count(doc, "/HL7Message/PID/PID.1[4]/*"));
    }

    @Test
    public void testSubcomponentsIgnoredByDefault() throws Exception {
        Document doc = parse("MSH|^~\\&\rPID|A&B^C");

        assertEquals("A&B", text(doc, "/HL7Message/PID/PID.1/PID.1.1"));
        assertEquals("C", text(doc, "/HL7Message/PID/PID.1/PID.1.2"));
    }

    @Test
    public void testHandleSubcomponents() throws Exception {
        Document doc = parse("MSH|^~\\&\rPID|A&B^C|X&&Y&", false, true);

        assertEquals("A", text(doc, "/HL7Message/PID/PID.1/PID.1.1/PID.1.1.1"));
        assertEquals("B", text(doc, "/HL7Message/PID/PID.1/PID.1.1/PID.1.1.2"));
        assertEquals("C", text(doc, "/HL7Message/PID/PID.1/PID.1.2"));

        // A field with subcomponents but no component separator is still split
        assertEquals("X", text(doc, "/HL7Message/PID/PID.2/PID.2.1/PID.2.1.1"));
        assertEquals(1, count(doc, "/HL7Message/PID/PID.2/PID.2.1/PID.2.1.2"));
        assertEquals("", text(doc, "/HL7Message/PID/PID.2/PID.2.1/PID.2.1.2"));
        assertEquals("Y", text(doc, "/HL7Message/PID/PID.2/PID.2.1/PID.2.1.3"));
        assertEquals(1, count(doc, "/HL7Message/PID/PID.2/PID.2.1/PID.2.1.4"));
    }

    @Test
    public void testCustomEncodingCharacters() throws Exception {
        Document doc = parse("MSH#*@!$#APP\rPID#A*B@C$D", true, true);

        assertEquals("#", text(doc, "/HL7Message/MSH/MSH.1"));
        assertEquals("*@!$", text(doc, "/HL7Message/MSH/MSH.2"));
        assertEquals("APP", text(doc, "/HL7Message/MSH/MSH.3/MSH.3.1"));
        assertEquals(2, count(doc, "/HL7Message/PID/PID.1"));
        assertEquals("A", text(doc, "/HL7Message/PID/PID.1[1]/PID.1.1"));
        assertEquals("B", text(doc, "/HL7Message/PID/PID.1[1]/PID.1.2"));
        assertEquals("C", text(doc, "/HL7Message/PID/PID.1[2]/PID.1.1/PID.1.1.1"));
        assertEquals("D", text(doc, "/HL7Message/PID/PID.1[2]/PID.1.1/PID.1.1.2"));
    }

    @Test
    public void testMissingEscapeCharacterIsRestored() throws Exception {
        // MIRTH-1544: ^~& is treated as ^~\&
        Document doc = parse("MSH|^~&|APP\rPID|A&B", false, true);

        assertEquals("^~\\&", text(doc, "/HL7Message/MSH/MSH.2"));
        assertEquals("A", text(doc, "/HL7Message/PID/PID.1/PID.1.1/PID.1.1.1"));
        assertEquals("B", text(doc, "/HL7Message/PID/PID.1/PID.1.1/PID.1.1.2"));
    }

    @Test
    public void testHeaderWithOnlyEncodingCharacters() throws Exception {
        assertEquals("^~", text(parse("MSH|^~"), "/HL7Message/MSH/MSH.2"));
        assertEquals("^~\\", text(parse("MSH|^~\\"), "/HL7Message/MSH/MSH.2"));
        assertEquals("^~\\&", text(parse("MSH|^~\\&"), "/HL7Message/MSH/MSH.2"));
    }

    @Test
    public void testMessageWithoutHeaderUsesDefaultSeparators() throws Exception {
        Document doc = parse("PID|1|A^B~C", true, false);

        assertEquals("1", text(doc, "/HL7Message/PID/PID.1/PID.1.1"));
        assertEquals("A", text(doc, "/HL7Message/PID/PID.2[1]/PID.2.1"));
        assertEquals("B", text(doc, "/HL7Message/PID/PID.2[1]/PID.2.2"));
        assertEquals("C", text(doc, "/HL7Message/PID/PID.2[2]/PID.2.1"));
    }

    @Test
    public void testSurroundingWhitespaceIsTrimmed() throws Exception {
        Document doc = parse("\n  MSH|^~\\&|APP\r  \n");

        assertEquals("APP", text(doc, "/HL7Message/MSH/MSH.3/MSH.3.1"));
        assertEquals(1, count(doc, "/HL7Message/*"));
    }

    @Test
    public void testMessageTooShort() throws Exception {
        ER7Reader reader = new ER7Reader(false, false, "\r");
        reader.setContentHandler(new DefaultHandler());

        try {
            reader.parse(new InputSource(new StringReader(" MSH| ")));
            fail("Expected SAXException");
        } catch (SAXException e) {
            assertTrue(e.getMessage().contains("too short"));
        }
    }

    @Test
    public void testContentHandlerRequired() throws Exception {
        try {
            new ER7Reader(false, false, "\r").parse(new InputSource(new StringReader("MSH|^~\\&")));
            fail("Expected SAXException");
        } catch (SAXException e) {
            assertTrue(e.getMessage().contains("ContentHandler"));
        }
    }

    // Whole messages and their quirks

    @Test
    public void testBasicMessage() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>APP</MSH.3.1></MSH.3><MSH.4><MSH.4.1>FAC</MSH.4.1></MSH.4></MSH>
                <PID><PID.1><PID.1.1>1</PID.1.1></PID.1><PID.2></PID.2>
                <PID.3><PID.3.1>123</PID.3.1><PID.3.2></PID.3.2><PID.3.3></PID.3.3><PID.3.4>MR</PID.3.4></PID.3>
                <PID.3><PID.3.1>456</PID.3.1><PID.3.2></PID.3.2><PID.3.3></PID.3.3><PID.3.4>SS</PID.3.4></PID.3>
                <PID.4></PID.4><PID.5><PID.5.1>DOE</PID.5.1><PID.5.2>JOHN</PID.5.2></PID.5></PID>
                <OBX><OBX.1><OBX.1.1>1</OBX.1.1></OBX.1><OBX.2><OBX.2.1>TX</OBX.2.1></OBX.2><OBX.3></OBX.3><OBX.4></OBX.4>
                <OBX.5><OBX.5.1><OBX.5.1.1>a</OBX.5.1.1><OBX.5.1.2>b</OBX.5.1.2></OBX.5.1><OBX.5.2>c</OBX.5.2></OBX.5></OBX>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|APP|FAC\rPID|1||123^^^MR~456^^^SS||DOE^JOHN\rOBX|1|TX|||a&b^c"));
    }

    @Test
    public void testBatchAndFileHeaders() throws Exception {
        String expected = """
                <HL7Message>
                <FHS><FHS.1>|</FHS.1><FHS.2>^~\\&amp;</FHS.2><FHS.3><FHS.3.1>F</FHS.3.1></FHS.3></FHS>
                <BHS><BHS.1>|</BHS.1><BHS.2>^~\\&amp;</BHS.2><BHS.3><BHS.3.1>B</BHS.3.1></BHS.3></BHS>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>M</MSH.3.1></MSH.3></MSH>
                <BTS><BTS.1><BTS.1.1>1</BTS.1.1></BTS.1></BTS>
                <FTS><FTS.1><FTS.1.1>1</FTS.1.1></FTS.1></FTS>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("FHS|^~\\&|F\rBHS|^~\\&|B\rMSH|^~\\&|M\rBTS|1\rFTS|1"));
    }

    @Test
    public void testHeaderFieldWithComponentsAndRepetitions() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3>
                <MSH.4><MSH.4.1>B</MSH.4.1><MSH.4.2>C</MSH.4.2></MSH.4>
                <MSH.4><MSH.4.1><MSH.4.1.1>D</MSH.4.1.1><MSH.4.1.2>E</MSH.4.1.2></MSH.4.1></MSH.4></MSH>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A|B^C~D&E"));
    }

    @Test
    public void testHeaderEndingAfterEncodingCharacters() throws Exception {
        assertEquals("<HL7Message><MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3></MSH.3></MSH></HL7Message>", read("MSH|^~\\&|"));
    }

    /** Without a subcomponent separator, {@code &} is ordinary text. */
    @Test
    public void testThreeEncodingCharacters() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID><PID.1><PID.1.1>1</PID.1.1></PID.1><PID.2></PID.2>
                <PID.3><PID.3.1>a</PID.3.1><PID.3.2>b&amp;c</PID.3.2></PID.3><PID.3><PID.3.1>d</PID.3.1></PID.3></PID>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\|A\rPID|1||a^b&c~d"));
    }

    @Test
    public void testTwoEncodingCharacters() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID><PID.1><PID.1.1>1</PID.1.1></PID.1><PID.2></PID.2>
                <PID.3><PID.3.1>a</PID.3.1><PID.3.2>b&amp;c</PID.3.2></PID.3><PID.3><PID.3.1>d</PID.3.1></PID.3>
                <PID.4><PID.4.1>e\\f</PID.4.1></PID.4></PID>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~|A\rPID|1||a^b&c~d|e\\f"));
    }

    /** Without a header there is no subcomponent separator, so {@code &} is plain text. */
    @Test
    public void testNoHeaderSubcomponents() throws Exception {
        assertEquals("<HL7Message><PID><PID.1><PID.1.1>1</PID.1.1></PID.1><PID.2><PID.2.1>a&amp;b</PID.2.1></PID.2></PID></HL7Message>", read("PID|1|a&b"));
    }

    /**
     * The separators come from a header matched without regard to case, but MSH.1 and MSH.2 are
     * only special for upper case names, so the encoding characters are split like field content.
     */
    @Test
    public void testLowerCaseHeader() throws Exception {
        String expected = """
                <HL7Message>
                <msh><msh.1><msh.1.1></msh.1.1><msh.1.2></msh.1.2></msh.1>
                <msh.1><msh.1.1><msh.1.1.1>\\</msh.1.1.1><msh.1.1.2></msh.1.1.2></msh.1.1></msh.1>
                <msh.2><msh.2.1>A</msh.2.1></msh.2></msh>
                <PID><PID.1><PID.1.1>1</PID.1.1></PID.1><PID.2></PID.2><PID.3><PID.3.1>a</PID.3.1><PID.3.2>b</PID.3.2></PID.3></PID>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("msh|^~\\&|A\rPID|1||a^b"));
    }

    /** Separators come from the first segment only; a later header is not reparsed. */
    @Test
    public void testLaterHeaderWithDifferentSeparators() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <MSH#*+!@#B*C></MSH#*+!@#B*C>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A\rMSH#*+!@#B*C"));
    }

    @Test
    public void testSegmentWithoutFields() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID></PID>
                <NTE><NTE.1><NTE.1.1>1</NTE.1.1></NTE.1></NTE>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A\rPID\rNTE|1"));
    }

    @Test
    public void testEmptySegmentsAreDropped() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID><PID.1><PID.1.1>1</PID.1.1></PID.1></PID>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A\r\r\rPID|1\r"));
    }

    /** Each segment name is trimmed, not only the message as a whole. */
    @Test
    public void testSegmentNameIsTrimmed() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID><PID.1><PID.1.1>1</PID.1.1></PID.1></PID>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A\r PID |1"));
    }

    @Test
    public void testMultiCharacterSegmentDelimiter() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID><PID.1><PID.1.1>1</PID.1.1></PID.1></PID>
                <NTE><NTE.1><NTE.1.1>1</NTE.1.1></NTE.1></NTE>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A\r\nPID|1\r\nNTE|1", true, true, "\r\n"));
    }

    /** A multi-character segment delimiter splits on any one of its characters. */
    @Test
    public void testMultiCharacterSegmentDelimiterSplitsOnEachCharacter() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID><PID.1><PID.1.1>a</PID.1.1></PID.1></PID>
                <b><b.1><b.1.1>c</b.1.1></b.1></b>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A\r\nPID|a\nb|c", true, true, "\r\n"));
    }

    /** Line breaks other than the segment delimiter stay in the value when read directly. */
    @Test
    public void testOtherLineBreakIsNotASegmentDelimiter() throws Exception {
        assertEquals("<HL7Message><MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A\nPID</MSH.3.1></MSH.3><MSH.4><MSH.4.1>1</MSH.4.1></MSH.4></MSH></HL7Message>", read("MSH|^~\\&|A\nPID|1"));
    }

    /** Escape sequences are not decoded. */
    @Test
    public void testEscapeSequencesPassThrough() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <OBX><OBX.1><OBX.1.1>1</OBX.1.1></OBX.1><OBX.2><OBX.2.1>FT</OBX.2.1></OBX.2><OBX.3></OBX.3><OBX.4></OBX.4>
                <OBX.5><OBX.5.1>line\\.br\\two \\F\\ \\E\\</OBX.5.1></OBX.5></OBX>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A\rOBX|1|FT|||line\\.br\\two \\F\\ \\E\\"));
    }

    @Test
    public void testXmlSpecialCharacters() throws Exception {
        String expected = """
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID><PID.1><PID.1.1><PID.1.1.1>&lt;a></PID.1.1.1><PID.1.1.2>"'</PID.1.1.2></PID.1.1></PID.1></PID>
                </HL7Message>
                """;
        assertEquals(compact(expected), read("MSH|^~\\&|A\rPID|<a>&\"'"));
    }

    @Test
    public void testToXml() throws Exception {
        ER7Serializer serializer = new ER7Serializer(new SerializerProperties(new HL7v2SerializationProperties(), new HL7v2DeserializationProperties(), null));
        String expected = """
                <?xml version="1.0" encoding="UTF-8"?>
                <HL7Message>
                <MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>
                <PID><PID.1><PID.1.1>1</PID.1.1></PID.1><PID.2></PID.2>
                <PID.3><PID.3.1>a</PID.3.1><PID.3.2><PID.3.2.1>b</PID.3.2.1><PID.3.2.2>c</PID.3.2.2></PID.3.2></PID.3>
                <PID.3><PID.3.1>d</PID.3.1></PID.3><PID.4><PID.4.1>&lt;x&gt;</PID.4.1></PID.4></PID>
                </HL7Message>
                """;
        assertEquals(compact(expected), serializer.toXML("MSH|^~\\&|A\nPID|1||a^b&c~d|<x>"));
    }

    @Test
    public void testToXmlWithoutConvertingLineBreaks() throws Exception {
        HL7v2SerializationProperties serializationProperties = new HL7v2SerializationProperties();
        serializationProperties.setConvertLineBreaks(false);
        ER7Serializer serializer = new ER7Serializer(new SerializerProperties(serializationProperties, new HL7v2DeserializationProperties(), null));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><HL7Message><MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A&#10;PID</MSH.3.1></MSH.3><MSH.4><MSH.4.1>1</MSH.4.1></MSH.4></MSH></HL7Message>", serializer.toXML("MSH|^~\\&|A\nPID|1"));
    }
}
