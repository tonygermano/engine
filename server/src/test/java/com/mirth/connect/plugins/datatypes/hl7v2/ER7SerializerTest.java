package com.mirth.connect.plugins.datatypes.hl7v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.SAXParseException;

import com.mirth.connect.donkey.model.message.MessageSerializerException;
import com.mirth.connect.model.datatype.SerializerProperties;

public class ER7SerializerTest {
	private static ER7Serializer serializer;
	// Strict parser with strict validation: XML input is parsed by HAPI (the XXE sink).
	private static ER7Serializer strictValidatingSerializer;

	@BeforeClass
	public static void setupClass() throws Exception {
		SerializerProperties serializerProperties = new SerializerProperties(new HL7v2SerializationProperties(), new HL7v2DeserializationProperties(), null);
		serializer = new ER7Serializer(serializerProperties);

		HL7v2SerializationProperties strictValidatingProperties = new HL7v2SerializationProperties();
		strictValidatingProperties.setUseStrictParser(true);
		strictValidatingProperties.setUseStrictValidation(true);
		strictValidatingSerializer = new ER7Serializer(new SerializerProperties(strictValidatingProperties, new HL7v2DeserializationProperties(), null));
	}

	private static ER7Serializer serializerWith(boolean convertLineBreaks, String deserializationSegmentDelimiter) {
		HL7v2SerializationProperties serializationProperties = new HL7v2SerializationProperties();
		serializationProperties.setConvertLineBreaks(convertLineBreaks);

		HL7v2DeserializationProperties deserializationProperties = new HL7v2DeserializationProperties();
		deserializationProperties.setSegmentDelimiter(deserializationSegmentDelimiter);

		return new ER7Serializer(new SerializerProperties(serializationProperties, deserializationProperties, null));
	}

	@Test
	public void testTransformWithoutSerializingConvertsToOutboundDelimiter() throws Exception {
		ER7Serializer inbound = serializerWith(true, "\\r");
		ER7Serializer outbound = serializerWith(true, "\\n");

		assertEquals("MSH|^~\\&|A\nPID|1", inbound.transformWithoutSerializing("MSH|^~\\&|A\rPID|1", outbound));
	}

	@Test
	public void testTransformWithoutSerializingNormalizesMixedLineBreaks() throws Exception {
		ER7Serializer sameEnds = serializerWith(true, "\\r");

		assertEquals("MSH|^~\\&|A\rPID|1\rNTE|1", sameEnds.transformWithoutSerializing("MSH|^~\\&|A\r\nPID|1\nNTE|1", sameEnds));
	}

	@Test
	public void testTransformWithoutSerializingStillConvertsWhenDelimitersMatch() throws Exception {
		/*
		 * convertLineBreaks is on, so the message goes through conversion and is returned even
		 * though it is unchanged. Only the convertLineBreaks-off case short-circuits to null.
		 */
		ER7Serializer sameEnds = serializerWith(true, "\\r");

		assertEquals("MSH|^~\\&|A\rPID|1", sameEnds.transformWithoutSerializing("MSH|^~\\&|A\rPID|1", sameEnds));
	}

	@Test
	public void testTransformWithoutSerializingReplacesDelimiterWhenLineBreakConversionIsOff() throws Exception {
		/*
		 * Reaches the StringUtils.replace branch of transformWithoutSerializing, which runs when
		 * the serialization and output delimiters differ and line breaks were not already converted
		 * straight to the output delimiter. That fast path is taken when convertLineBreaks is on
		 * and the serialization delimiter is CR, LF or CRLF, so this test turns convertLineBreaks
		 * off.
		 */
		ER7Serializer inbound = serializerWith(false, "\\r");
		ER7Serializer outbound = serializerWith(true, "\\n");

		assertEquals("MSH|^~\\&|A\nPID|1", inbound.transformWithoutSerializing("MSH|^~\\&|A\rPID|1", outbound));
	}

	@Test
	public void testTransformWithoutSerializingReturnsNullWhenNothingToDo() throws Exception {
		ER7Serializer inbound = serializerWith(false, "\\r");
		ER7Serializer outbound = serializerWith(true, "\\r");

		assertNull(inbound.transformWithoutSerializing("MSH|^~\\&|A\rPID|1", outbound));
	}

	@Test
	public void testFromXMLWithExternalDTD() throws Exception {
		String xml = FileUtils.readFileToString(new File("tests/test-xxe-hl7-example.xml"), "UTF-8");

		boolean exceptionThrown = false;
		try {
			serializer.fromXML(xml);
		} catch (MessageSerializerException e) {
			exceptionThrown = true;

			// See https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html#jaxp-documentbuilderfactory-saxparserfactory-and-dom4j
			assertTrue(e.getCause() instanceof SAXParseException);
		}

		assertTrue(exceptionThrown);
	}

	@Test
	public void testValidFromXMLWithExternalDTD() throws Exception {
		String xml = FileUtils.readFileToString(new File("tests/test-xxe-hl7-example-valid.xml"), "UTF-8");

		boolean exceptionThrown = false;
		try {
			serializer.fromXML(xml);
		} catch (MessageSerializerException e) {
			exceptionThrown = true;

		}

		assertFalse(exceptionThrown);
	}

	@Test
	public void testToXmlStrictValidatingRejectsExternalDTD() throws Exception {
		// A DOCTYPE-bearing message on the strict-parser toXML path is the unauthenticated MLLP XXE
		// vector (HAPI 2.3 resolved external entities). It must be rejected rather than have its
		// external entity resolved. Note: this asserts the intended behavior; the discriminating
		// before/after proof that the override (not incidental parser behavior) closes the XXE is the
		// live MLLP reproduction documented in the PR.
		String xml = FileUtils.readFileToString(new File("tests/test-xxe-hl7-strict-mllp.xml"), "UTF-8");

		boolean exceptionThrown = false;
		try {
			strictValidatingSerializer.toXML(xml);
		} catch (MessageSerializerException e) {
			exceptionThrown = true;

			// The rejection must be the DOCTYPE being disallowed, not some incidental parse failure.
			Throwable rootCause = ExceptionUtils.getRootCause(e);
			assertTrue(rootCause instanceof SAXParseException);
			assertTrue(rootCause.getMessage().contains("DOCTYPE"));
		}

		assertTrue(exceptionThrown);
	}

	@Test
	public void testToXmlStrictValidatingAllowsValidXml() throws Exception {
		// The same message without a DOCTYPE is legitimate HL7 v2.x XML and must still round-trip, so
		// the hardening does not break the strict parser's XML support.
		String xml = FileUtils.readFileToString(new File("tests/test-xxe-hl7-strict-mllp-valid.xml"), "UTF-8");

		boolean exceptionThrown = false;
		try {
			strictValidatingSerializer.toXML(xml);
		} catch (MessageSerializerException e) {
			exceptionThrown = true;
		}

		assertFalse(exceptionThrown);
	}

	@Test
	public void testToXmlStrictValidatingKeepsCdataContent() throws Exception {
		// HAPI reads only text nodes into a field, so CDATA content is silently dropped unless the
		// parser coalesces it into the surrounding text.
		String xml = "<ACK xmlns=\"urn:hl7-org:v2xml\"><MSH><MSH.1>|</MSH.1><MSH.2>^~\\&amp;</MSH.2><MSH.3><HD.1><![CDATA[CDATA-APP]]></HD.1></MSH.3><MSH.9><MSG.1>ACK</MSG.1></MSH.9><MSH.10>1</MSH.10><MSH.12><VID.1>2.4</VID.1></MSH.12></MSH><MSA><MSA.1>AA</MSA.1><MSA.2>1</MSA.2></MSA></ACK>";

		assertTrue(strictValidatingSerializer.toXML(xml).contains("CDATA-APP"));
	}
}
