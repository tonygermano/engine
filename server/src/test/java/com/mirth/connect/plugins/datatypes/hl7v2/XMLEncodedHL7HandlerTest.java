// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Tony Germano <tony@germano.name>

package com.mirth.connect.plugins.datatypes.hl7v2;

import static org.junit.Assert.assertEquals;

import org.junit.BeforeClass;
import org.junit.Test;

import com.mirth.connect.model.datatype.SerializerProperties;

/**
 * Characterization tests for {@link XMLEncodedHL7Handler}, the non-strict XML to ER7 path of
 * {@link ER7Serializer#fromXML(String)}. These pin the current output, including its quirks, so any
 * change to it is deliberate.
 */
public class XMLEncodedHL7HandlerTest {
    private static ER7Serializer serializer;

    @BeforeClass
    public static void setupClass() {
        serializer = createSerializer(true, true);
    }

    private static ER7Serializer createSerializer(boolean handleRepetitions, boolean handleSubcomponents) {
        HL7v2SerializationProperties serializationProperties = new HL7v2SerializationProperties();
        serializationProperties.setHandleRepetitions(handleRepetitions);
        serializationProperties.setHandleSubcomponents(handleSubcomponents);
        return new ER7Serializer(new SerializerProperties(serializationProperties, new HL7v2DeserializationProperties(), null));
    }

    private static String fromXml(String xml) throws Exception {
        return serializer.fromXML(xml);
    }

    private static String message(String body) {
        return "<HL7Message>" + body + "</HL7Message>";
    }

    // Full messages and round trips

    @Test
    public void testHeaderAndSegments() throws Exception {
        String xml = message("<MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>" + "<OBX><OBX.1><OBX.1.1>1</OBX.1.1></OBX.1><OBX.5><OBX.5.1>X</OBX.5.1></OBX.5></OBX>");
        assertEquals("MSH|^~\\&|A\rOBX|1||||X\r", fromXml(xml));
    }

    @Test
    public void testRoundTripAppendsTrailingSegmentDelimiter() throws Exception {
        String[] messages = {
                "MSH|^~\\&|APP|FAC|||20260101||ADT^A01|1|P|2.5\rPID|1||123^^^MR~456^^^SS||DOE^JOHN^^^^^L||19700101|M\rOBX|1|TX|||a&b^c||||||F\rNTE|1\rNTE|2",
                "MSH|^~\\&|A||||||||||\rPID|||||||",
                "MSH|^~\\&|A\rPID|1|^^|~~|&&^&",
                "MSH|^~\\&|A\rOBX|1|FT|||line\\.br\\two \\F\\ \\E\\" };

        for (boolean handleRepetitions : new boolean[] { true, false }) {
            for (boolean handleSubcomponents : new boolean[] { true, false }) {
                ER7Serializer roundTripSerializer = createSerializer(handleRepetitions, handleSubcomponents);

                for (String er7 : messages) {
                    String label = "handleRepetitions=" + handleRepetitions + ", handleSubcomponents=" + handleSubcomponents + ": " + er7;
                    assertEquals(label, er7 + "\r", roundTripSerializer.fromXML(roundTripSerializer.toXML(er7)));
                }
            }
        }
    }

    @Test
    public void testNamespacedXml() throws Exception {
        assertEquals("PID|||A\r", fromXml("<HL7Message xmlns=\"urn:hl7-org:v2xml\"><PID><PID.3><PID.3.1>A</PID.3.1></PID.3></PID></HL7Message>"));
    }

    // Gap filling

    @Test
    public void testComponentGap() throws Exception {
        assertEquals("PID|||||A^^^B\r", fromXml(message("<PID><PID.5><PID.5.1>A</PID.5.1><PID.5.4>B</PID.5.4></PID.5></PID>")));
    }

    @Test
    public void testSubcomponentGap() throws Exception {
        assertEquals("PID|||^^^A&&B\r", fromXml(message("<PID><PID.3><PID.3.4><PID.3.4.1>A</PID.3.4.1><PID.3.4.3>B</PID.3.4.3></PID.3.4></PID.3></PID>")));
    }

    @Test
    public void testSubcomponentGapAfterComponentGap() throws Exception {
        assertEquals("PID|||X^^^&B\r", fromXml(message("<PID><PID.3><PID.3.1>X</PID.3.1><PID.3.4><PID.3.4.2>B</PID.3.4.2></PID.3.4></PID.3></PID>")));
    }

    @Test
    public void testLeadingGapsAtEveryLevel() throws Exception {
        assertEquals("PID|||^&B\r", fromXml(message("<PID><PID.3><PID.3.2><PID.3.2.2>B</PID.3.2.2></PID.3.2></PID.3></PID>")));
    }

    // Repetitions

    @Test
    public void testRepetition() throws Exception {
        assertEquals("PID|||A~B^C\r", fromXml(message("<PID><PID.3><PID.3.1>A</PID.3.1></PID.3><PID.3><PID.3.1>B</PID.3.1><PID.3.2>C</PID.3.2></PID.3></PID>")));
    }

    @Test
    public void testEmptyRepetition() throws Exception {
        assertEquals("PID|||A~~B\r", fromXml(message("<PID><PID.3><PID.3.1>A</PID.3.1></PID.3><PID.3/><PID.3><PID.3.1>B</PID.3.1></PID.3></PID>")));
    }

    /** A repeated field name that is not adjacent is not a repetition; it becomes the next field. */
    @Test
    public void testNonAdjacentRepeatedField() throws Exception {
        assertEquals("PID|||A|B|C\r", fromXml(message("<PID><PID.3><PID.3.1>A</PID.3.1></PID.3><PID.4><PID.4.1>B</PID.4.1></PID.4><PID.3><PID.3.1>C</PID.3.1></PID.3></PID>")));
    }

    /** A repeated component name is not a repetition; it becomes the next component. */
    @Test
    public void testRepeatedComponentName() throws Exception {
        assertEquals("PID|||||A^B\r", fromXml(message("<PID><PID.5><PID.5.1>A</PID.5.1><PID.5.1>B</PID.5.1></PID.5></PID>")));
    }

    /** Two segments in a row with one field each must not be joined as a repetition. */
    @Test
    public void testConsecutiveOneFieldSegments() throws Exception {
        assertEquals("NTE|1\rNTE|2\r", fromXml(message("<NTE><NTE.1><NTE.1.1>1</NTE.1.1></NTE.1></NTE><NTE><NTE.1><NTE.1.1>2</NTE.1.1></NTE.1></NTE>")));
    }

    // Ordering and placement: output follows document order, not element names

    @Test
    public void testOutOfOrderFields() throws Exception {
        assertEquals("PID|||||A|B\r", fromXml(message("<PID><PID.5><PID.5.1>A</PID.5.1></PID.5><PID.3><PID.3.1>B</PID.3.1></PID.3></PID>")));
    }

    @Test
    public void testOutOfOrderComponents() throws Exception {
        assertEquals("PID|||||^^A^B\r", fromXml(message("<PID><PID.5><PID.5.3>A</PID.5.3><PID.5.1>B</PID.5.1></PID.5></PID>")));
    }

    /** Depth comes from the number of dots and the index from the last part of the name. */
    @Test
    public void testOutOfPlaceName() throws Exception {
        assertEquals("PID|||||value\r", fromXml(message("<PID><PID.5><OBR.16.1>value</OBR.16.1></PID.5></PID>")));
    }

    @Test
    public void testMissingFieldWrapper() throws Exception {
        assertEquals("PID|^value\r", fromXml(message("<PID><PID.5.2>value</PID.5.2></PID>")));
    }

    @Test
    public void testMissingComponentWrapper() throws Exception {
        assertEquals("PID|||||&value\r", fromXml(message("<PID><PID.5><PID.5.2.2>value</PID.5.2.2></PID.5></PID>")));
    }

    /** Elements nested deeper than subcomponents are dropped, along with their text. */
    @Test
    public void testNestingDeeperThanSubcomponent() throws Exception {
        assertEquals("PID||||B\r", fromXml(message("<PID><PID.3><PID.3.1><PID.3.1.1><PID.3.1.1.1>A</PID.3.1.1.1></PID.3.1.1></PID.3.1></PID.3><PID.4><PID.4.1>B</PID.4.1></PID.4></PID>")));
    }

    // Header segments and encoding characters

    @Test
    public void testNonDefaultSeparators() throws Exception {
        String xml = message("<MSH><MSH.1>#</MSH.1><MSH.2>*+!@</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1><MSH.3.2><MSH.3.2.1>B</MSH.3.2.1><MSH.3.2.2>C</MSH.3.2.2></MSH.3.2></MSH.3>" + "<MSH.4><MSH.4.1>D</MSH.4.1></MSH.4><MSH.4><MSH.4.1>E</MSH.4.1></MSH.4></MSH><PID><PID.3><PID.3.1>X</PID.3.1></PID.3></PID>");
        assertEquals("MSH#*+!@#A*B@C#D+E\rPID###X\r", fromXml(xml));
    }

    @Test
    public void testThreeEncodingCharacters() throws Exception {
        String xml = message("<MSH><MSH.1>|</MSH.1><MSH.2>^~\\</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1><MSH.3.2>B</MSH.3.2></MSH.3></MSH><PID><PID.3><PID.3.1>X</PID.3.1></PID.3></PID>");
        assertEquals("MSH|^~\\|A^B\rPID|||X\r", fromXml(xml));
    }

    @Test
    public void testTwoEncodingCharacters() throws Exception {
        String xml = message("<MSH><MSH.1>|</MSH.1><MSH.2>^~</MSH.2><MSH.3><MSH.3.1>A</MSH.3.1><MSH.3.2>B</MSH.3.2></MSH.3></MSH><PID><PID.3><PID.3.1>X</PID.3.1></PID.3></PID>");
        assertEquals("MSH|^~|A^B\rPID|||X\r", fromXml(xml));
    }

    /** Without a subcomponent separator, subcomponents are concatenated. */
    @Test
    public void testSubcomponentsWithoutSubcomponentSeparator() throws Exception {
        String xml = message("<MSH><MSH.1>|</MSH.1><MSH.2>^~\\</MSH.2></MSH><PID><PID.3><PID.3.1><PID.3.1.1>X</PID.3.1.1><PID.3.1.2>Y</PID.3.1.2></PID.3.1></PID.3><PID.4><PID.4.1>Z</PID.4.1></PID.4></PID>");
        assertEquals("MSH|^~\\\rPID|||XY|Z\r", fromXml(xml));
    }

    @Test
    public void testHeaderWithoutEncodingFields() throws Exception {
        assertEquals("MSH|||A\r", fromXml(message("<MSH><MSH.3><MSH.3.1>A</MSH.3.1></MSH.3></MSH>")));
    }

    // Partial messages (MIRTH-2078)

    @Test
    public void testPartialComponent() throws Exception {
        assertEquals("A&&B", fromXml("<PID.3.4><PID.3.4.1>A</PID.3.4.1><PID.3.4.3>B</PID.3.4.3></PID.3.4>"));
    }

    @Test
    public void testPartialSubcomponent() throws Exception {
        assertEquals("A", fromXml("<PID.3.4.2>A</PID.3.4.2>"));
    }

    // Empty elements

    @Test
    public void testEmptyElements() throws Exception {
        assertEquals("PID|||A^|\r", fromXml(message("<PID><PID.1/><PID.2><PID.2.1/></PID.2><PID.3><PID.3.1>A</PID.3.1><PID.3.2/></PID.3><PID.4/></PID>")));
    }

    @Test
    public void testEmptySegment() throws Exception {
        assertEquals("PID|\rNTE|1\r", fromXml(message("<PID/><NTE><NTE.1><NTE.1.1>1</NTE.1.1></NTE.1></NTE>")));
    }

    // Text content

    /** Separator characters in values are written raw; nothing is escaped. */
    @Test
    public void testSeparatorsInValueAreNotEscaped() throws Exception {
        assertEquals("PID|||a^b~c&d\\e\r", fromXml(message("<PID><PID.3><PID.3.1>a^b~c&amp;d\\e</PID.3.1></PID.3></PID>")));
    }

    @Test
    public void testEscapeSequencesInValuePassThrough() throws Exception {
        assertEquals("PID|||a\\F\\b\\.br\\c\r", fromXml(message("<PID><PID.3><PID.3.1>a\\F\\b\\.br\\c</PID.3.1></PID.3></PID>")));
    }

    /** A value that is exactly the field separator is dropped (to skip MSH.1), but one containing it is not. */
    @Test
    public void testValueEqualToFieldSeparator() throws Exception {
        assertEquals("PID|||^x|y\r", fromXml(message("<PID><PID.3><PID.3.1>|</PID.3.1><PID.3.2>x|y</PID.3.2></PID.3></PID>")));
    }

    @Test
    public void testEntitiesInValue() throws Exception {
        assertEquals("PID|||a&b<c|d\r", fromXml(message("<PID><PID.3><PID.3.1>a&amp;b&lt;c|d</PID.3.1></PID.3></PID>")));
    }

    /** Text before the first child is kept; text after a child element ends is dropped. */
    @Test
    public void testMixedContent() throws Exception {
        assertEquals("PID|||leadA\r", fromXml(message("<PID><PID.3>lead<PID.3.1>A</PID.3.1>trail</PID.3></PID>")));
    }

    @Test
    public void testFieldTextWithoutComponents() throws Exception {
        assertEquals("PID|||A|B\r", fromXml(message("<PID><PID.3>A</PID.3><PID.4>B</PID.4></PID>")));
    }

    // Segment delimiter

    @Test
    public void testMultiCharacterSegmentDelimiter() throws Exception {
        HL7v2DeserializationProperties deserializationProperties = new HL7v2DeserializationProperties();
        deserializationProperties.setSegmentDelimiter("\\r\\n");
        ER7Serializer crlfSerializer = new ER7Serializer(new SerializerProperties(new HL7v2SerializationProperties(), deserializationProperties, null));
        assertEquals("PID|||A\r\nNTE|1\r\n", crlfSerializer.fromXML(message("<PID><PID.3><PID.3.1>A</PID.3.1></PID.3></PID><NTE><NTE.1><NTE.1.1>1</NTE.1.1></NTE.1></NTE>")));
    }
}
