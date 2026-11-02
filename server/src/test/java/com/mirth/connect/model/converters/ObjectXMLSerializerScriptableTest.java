// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Tony Germano <tony@germano.name>

package com.mirth.connect.model.converters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import com.mirth.connect.client.core.Version;
import com.mirth.connect.donkey.util.MapUtil;
import com.mirth.connect.donkey.util.xstream.SerializerException;

/// Characterization tests documenting how [ObjectXMLSerializer] currently handles the values a
/// user script can put into the channel map, global map, response map, etc. These assertions record
/// existing behavior, including behavior that is lossy or arguably wrong, so that changes to
/// [JavaScriptObjectConverter] (or anything else in the serialization path) are made deliberately.
/// When a change here is intended, update the assertion and its comment.
///
/// Values are produced the way they are on a running server:
///
/// - The script runs in the interpreter at language version ES6 in a per-script scope whose
///   prototype is a sealed shared scope initialized like
///   `JavaScriptBuilder.generateGlobalSealedScript()`. That matters: once E4X is initialized or the
///   shared scope is sealed, Rhino objects that fall through to XStream reflection can no longer be
///   serialized at all.
/// - The per-script scope holds an E4X `msg`, as filter and transformer scopes do for XML-based
///   data types. Results that fall back to XStream reflection depend on what that scope holds,
///   because reflection reaches it through the object's parent scope.
/// - The value is passed through a Java `Map.put`, so Rhino's Java method argument coercion decides
///   what actually lands in the map (e.g. JS numbers become [Double]).
///
/// Two levels are checked:
///
/// - [ObjectXMLSerializer#serialize(Object)] of the single value.
/// - [MapUtil#serializeMap] followed by [MapUtil#deserializeMap], which is how `JdbcDao` stores and
///   reloads map content. When a value fails to serialize, MapUtil stores its Java `toString()`
///   instead.
///
/// On a server, `JdbcDao` uses the serializer of the channel's `MirthContextFactory`, which is a
/// separate [ObjectXMLSerializer] instance configured the same way as the singleton used here.
///
/// Serialization normally happens after the script has finished, with no Rhino [Context] on the
/// thread. It can also happen while a Context is still entered, e.g. when a script calls
/// `router.routeMessage()` and the downstream channel stores its maps on the same thread. A few
/// types behave differently in those two cases; tests ending in `OutsideContext` and
/// `InsideContext` document both.
public class ObjectXMLSerializerScriptableTest {

    private ScriptableObject sealedSharedScope;

    @BeforeClass
    public static void setup() throws Exception {
        try {
            ObjectXMLSerializer.getInstance().init(Version.getLatest().toString());
        } catch (Exception e) {
            // Ignore if it has already been initialized
        }
    }

    /*
     * A new shared scope per test, because Rhino lazily initializes built-in prototype methods and
     * some results depend on whether that has already happened (see
     * testObjectContainingDateOutsideContextAfterToJSONInitialized).
     */
    @Before
    public void createSealedSharedScope() throws Exception {
        Context context = enterContext();
        try {
            // Mirrors JavaScriptScopeUtil.createSealedSharedScope and JavaScriptBuilder.generateGlobalSealedScript, without its String.prototype.trim override
            sealedSharedScope = new ImporterTopLevel(context);
            context.evaluateString(sealedSharedScope, """
                    importPackage(Packages.com.mirth.connect.userutil);
                    importPackage(Packages.com.mirth.connect.server.userutil);
                    regex = new RegExp('');
                    xml = new XML('');
                    xmllist = new XMLList();
                    namespace = new Namespace();
                    qname = new QName();
                    XML.ignoreWhitespace=true;
                    XML.prettyPrinting=false;
                    """, "sealed", 1, null);
            sealedSharedScope.sealObject();
        } finally {
            Context.exit();
        }
    }

    /*
     * JavaScript primitives. These are converted to Java types by Rhino before they reach the map,
     * so the serializer never sees a Scriptable.
     */

    @Test
    public void testStringPrimitive() throws Exception {
        // Concatenation produces a ConsString in Rhino, which is coerced to java.lang.String
        Object value = jsValue("'abc' + 123");
        assertEquals(String.class, value.getClass());
        assertEquals("<string>abc123</string>", serialize(value));
        assertEquals("abc123", storedMapValue(value));
    }

    @Test
    public void testNumberPrimitivesBecomeDoubles() throws Exception {
        Object value = jsValue("1");
        assertEquals(Double.valueOf(1), value);
        assertEquals("<double>1.0</double>", serialize(value));
        assertEquals(Double.valueOf(1), storedMapValue(value));

        assertEquals(Double.valueOf(1.5), storedMapValue(jsValue("1.5")));
        assertEquals(Double.valueOf(Double.NaN), storedMapValue(jsValue("NaN")));
        assertEquals(Double.valueOf(Double.POSITIVE_INFINITY), storedMapValue(jsValue("Infinity")));
    }

    @Test
    public void testBooleanPrimitive() throws Exception {
        Object value = jsValue("true");
        assertEquals(Boolean.TRUE, value);
        assertEquals("<boolean>true</boolean>", serialize(value));
        assertEquals(Boolean.TRUE, storedMapValue(value));
    }

    @Test
    public void testNull() throws Exception {
        assertNull(jsValue("null"));
        assertEquals("<null/>", serialize(null));
        assertNull(storedMapValue(null));
    }

    @Test
    public void testUndefinedBecomesString() throws Exception {
        // Rhino coerces undefined to the string "undefined" when passed as a java.lang.Object argument
        Object value = jsValue("undefined");
        assertEquals("undefined", value);
        assertEquals("undefined", storedMapValue(value));
    }

    /*
     * Objects (NativeObject). JavaScriptObjectConverter writes JSON.stringify output, and
     * MirthMapperWrapper names the element "string", so these deserialize as java.lang.String.
     */

    @Test
    public void testObjectSerializesAsJsonString() throws Exception {
        Object value = jsValue("({a: 1, b: 'x', c: [1, 2], d: {e: null}})");
        assertEquals(NativeObject.class, value.getClass());
        assertSerializesAsString("{\"a\":1,\"b\":\"x\",\"c\":[1,2],\"d\":{\"e\":null}}", value);
        assertEquals("{\"a\":1,\"b\":\"x\",\"c\":[1,2],\"d\":{\"e\":null}}", storedMapValue(value));
    }

    @Test
    public void testObjectOmitsFunctionAndUndefinedProperties() throws Exception {
        assertEquals("{\"a\":1}", storedMapValue(jsValue("({a: 1, f: function() {}, u: undefined})")));
    }

    @Test
    public void testObjectOmitsInheritedProperties() throws Exception {
        assertEquals("{\"x\":1}", storedMapValue(jsValue("(function() { function P(x) { this.x = x; } P.prototype.y = 2; return new P(1); })()")));
    }

    @Test
    public void testObjectWithNullPrototype() throws Exception {
        assertEquals("{}", storedMapValue(jsValue("Object.create(null)")));
    }

    @Test
    public void testFrozenObject() throws Exception {
        assertEquals("{\"a\":1}", storedMapValue(jsValue("Object.freeze({a: 1})")));
    }

    @Test
    public void testParsedJsonObject() throws Exception {
        assertEquals("{\"a\":[1,{\"b\":2}]}", storedMapValue(jsValue("JSON.parse('{\"a\":[1,{\"b\":2}]}')")));
    }

    @Test
    public void testObjectNumberEdgeCases() throws Exception {
        // Standard JSON.stringify behavior: non-finite numbers become null, -0 becomes 0
        assertEquals("{\"big\":12345678901234567000,\"neg0\":0,\"inf\":null,\"nan\":null}", storedMapValue(jsValue("({big: 12345678901234567890, neg0: -0, inf: Infinity, nan: NaN})")));
    }

    @Test
    public void testObjectWithToJSON() throws Exception {
        // The toJSON result is used, so the stored string can be a quoted JSON string rather than an object
        assertEquals("\"custom\"", storedMapValue(jsValue("({toJSON: function() { return 'custom'; }})")));
        assertEquals("{\"x\":\"custom\"}", storedMapValue(jsValue("({x: {toJSON: function() { return 'custom'; }}})")));
    }

    @Test
    public void testObjectContainingJavaObjects() throws Exception {
        assertEquals("{\"l\":[1,\"x\"]}", storedMapValue(jsValue("({l: java.util.Arrays.asList(1, 'x')})")));
        assertEquals("{\"sb\":\"sb\"}", storedMapValue(jsValue("({sb: new java.lang.StringBuilder('sb')})")));
    }

    @Test
    public void testObjectContainingRegExp() throws Exception {
        // Standard JSON.stringify behavior: a RegExp has no own enumerable properties
        assertEquals("{}", storedMapValue(jsValue("({r: /a/})")));
    }

    @Test
    public void testObjectContainingDateOutsideContext() throws Exception {
        // Date.prototype.toJSON needs a current Context, so JSON.stringify throws and the converter
        // falls back to reflection, which fails on the scope chain. MapUtil then stores toString().
        Object value = jsValue("({d: new Date(0)})");
        assertSerializeFails(value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testObjectContainingDateOutsideContextAfterToJSONInitialized() throws Exception {
        /*
         * Rhino creates Date.prototype.toJSON lazily in the shared scope, and creating it is what
         * needs the Context. Once any script using the same shared scope has touched it (e.g.
         * JSON.stringify(new Date()), or a serialization while a Context was entered), serialization
         * outside a Context succeeds. On a server the result depends on what has run before.
         */
        jsValue("Date.prototype.toJSON");
        Object value = jsValue("({d: new Date(0)})");
        assertEquals("{\"d\":\"1970-01-01T00:00:00.000Z\"}", storedMapValue(value));
    }

    @Test
    public void testObjectContainingDateInsideContext() throws Exception {
        Object value = jsValue("({d: new Date(0)})");
        assertEquals("{\"d\":\"1970-01-01T00:00:00.000Z\"}", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testObjectWithGetterOutsideContext() throws Exception {
        Object value = jsValue("({get g() { return 1; }})");
        assertSerializeFails(value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testObjectWithGetterInsideContext() throws Exception {
        Object value = jsValue("({get g() { return 1; }})");
        assertEquals("{\"g\":1}", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testObjectContainingPrimitiveWrappersOutsideContext() throws Exception {
        Object value = jsValue("({n: new Number(1), s: new String('s')})");
        assertSerializeFails(value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testObjectContainingPrimitiveWrappersInsideContext() throws Exception {
        Object value = jsValue("({n: new Number(1), s: new String('s')})");
        assertEquals("{\"n\":1,\"s\":\"s\"}", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testCyclicObjectOutsideContext() throws Exception {
        Object value = jsValue("(function() { var o = {}; o.self = o; return o; })()");
        assertSerializeFails(value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testCyclicObjectInsideContext() throws Exception {
        /*
         * JSON.stringify throws on the cycle and the converter falls back to reflection, which
         * reaches the per-script scope through the object's parent scope. With an E4X msg in that
         * scope, reflection fails, and MapUtil stores toString().
         */
        Object value = jsValue("(function() { var o = {}; o.self = o; return o; })()");
        inContext(() -> {
            assertSerializeFails(value);
            return null;
        });
        assertEquals("[object Object]", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testCyclicObjectInsideContextWritesScopeVariables() throws Exception {
        /*
         * Without E4X in the per-script scope, the reflection fallback succeeds. The prototype and
         * parent scope fields are NativeObjects, so JavaScriptObjectConverter writes each as
         * JSON.stringify output, and the parent scope is the per-script scope with the script's
         * top-level variables. The result is a <string> element with child elements, which
         * deserializes to the whitespace between them.
         */
        Object value = jsValueInScope("var topVar = 'v'; msg = 'raw';", "(function() { var o = {}; o.self = o; return o; })()");
        String xml = inContext(() -> serialize(value));
        assertTrue(xml, xml.startsWith("<string>"));
        assertTrue(xml, xml.contains("{&quot;topVar&quot;:&quot;v&quot;,&quot;msg&quot;:&quot;raw&quot;"));
        Object stored = inContext(() -> storedMapValue(value));
        assertTrue(stored instanceof String);
        assertEquals("", ((String) stored).trim());
    }

    @Test
    public void testObjectContainingXmlOutsideContext() throws Exception {
        // e.g. channelMap.put('patient', {name: msg['PID']['PID.5']})
        Object value = jsValue("({x: new XML('<a1><b>1</b></a1>')})");
        assertSerializeFails(value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testObjectContainingXmlInsideContext() throws Exception {
        // JSON.stringify throws on the XML value, and the reflection fallback fails on the scope's msg
        Object value = jsValue("({x: new XML('<a1><b>1</b></a1>')})");
        assertEquals("[object Object]", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testObjectContainingJavaDate() throws Exception {
        // The patched JSON.stringify unwraps the Java object, and a non-JS value that isn't a collection
        // or array is skipped like undefined, so the whole property is lost
        assertEquals("{}", storedMapValue(jsValue("({d: new java.util.Date(0)})")));
    }

    /*
     * Java values nested in JS objects and arrays. Newer Rhino versions decide these with the
     * Context's javaToJSONConverter (and write Java maps as objects); these tests record what the
     * patched JSON.stringify in Rhino 1.7.13 does.
     */

    @Test
    public void testObjectContainingOtherJavaValues() throws Exception {
        // Like java.util.Date, these are skipped like undefined
        assertEquals("{}", storedMapValue(jsValue("({i: java.time.Instant.ofEpochMilli(0)})")));
        assertEquals("{}", storedMapValue(jsValue("({e: java.time.DayOfWeek.MONDAY})")));
        assertEquals("{}", storedMapValue(jsValue("({u: java.util.UUID.fromString('00000000-0000-0000-0000-000000000001')})")));
        assertEquals("{}", storedMapValue(jsValue("({c: java.lang.Character.valueOf(120)})")));
        assertEquals("{}", storedMapValue(jsValue("({o: new java.lang.Object()})")));
        // e.g. from database results
        assertEquals("{}", storedMapValue(jsValue("({t: new java.sql.Timestamp(0)})")));
        // Java numbers, strings and booleans are kept
        assertEquals("{\"b\":1.5}", storedMapValue(jsValue("({b: new java.math.BigDecimal('1.50')})")));
        assertEquals("{\"s\":\"s\"}", storedMapValue(jsValue("({s: java.lang.String.valueOf('s')})")));
        assertEquals("{\"t\":true,\"f\":false}", storedMapValue(jsValue("({t: java.lang.Boolean.valueOf(true), f: java.lang.Boolean.FALSE})")));
    }

    @Test
    public void testObjectContainingJavaNumbers() throws Exception {
        assertEquals("{\"a\":123}", storedMapValue(jsValue("({a: new java.math.BigInteger('123')})")));
        // Java numbers are written as doubles, so a BigInteger too large for one is written as null...
        assertEquals("{\"a\":null}", storedMapValue(jsValue("({a: java.math.BigInteger.TEN.pow(400)})")));
        // ...and a long loses precision beyond 2^53
        assertEquals("{\"a\":9007199254740992}", storedMapValue(jsValue("({a: java.lang.Long.valueOf('9007199254740993')})")));
    }

    @Test
    public void testObjectContainingJavaArrays() throws Exception {
        // Only Object[] is written as an array; primitive arrays are skipped
        assertEquals("{\"a\":[\"x\",1]}", storedMapValue(jsValue("({a: java.util.Arrays.asList('x', 1).toArray()})")));
        assertEquals("{}", storedMapValue(jsValue("({a: java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 2)})")));
        assertEquals("{}", storedMapValue(jsValue("({a: new java.lang.String('ab').getBytes()})")));
    }

    @Test
    public void testObjectContainingJavaCollectionOfJavaValues() throws Exception {
        /*
         * Elements of a Java collection are written without being wrapped, so Java collections,
         * dates and maps in it are written as null. A JS object in it is kept.
         */
        String expression = "({a: java.util.Arrays.asList(java.util.Arrays.asList(1), new java.util.Date(0), java.util.Collections.singletonMap('k', 'v'), {x: 1})})";
        assertEquals("{\"a\":[null,null,null,{\"x\":1}]}", storedMapValue(jsValue(expression)));
    }

    @Test
    public void testToJSONReturningJavaObjectOutsideContext() throws Exception {
        // Calling a Java method from toJSON needs a Context
        Object value = jsValue("({toJSON: function() { return java.util.Arrays.asList(1); }})");
        assertSerializeFails(value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testToJSONReturningJavaObjectInsideContext() throws Exception {
        // The Java object isn't unwrapped after toJSON, so it is written as a JS object; a List's properties are its indices
        Object value = jsValue("({toJSON: function() { return java.util.Arrays.asList(1); }})");
        assertEquals("{\"0\":1}", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testObjectContainingJavaMap() throws Exception {
        // Unlike Java collections, Java maps are skipped, even with entries
        assertEquals("{}", storedMapValue(jsValue("({m: java.util.Collections.singletonMap('k', 'v')})")));
        assertEquals("{}", storedMapValue(jsValue("({m: java.util.Collections.singletonMap('d', new java.util.Date(0))})")));
    }

    @Test
    public void testArrayContainingJavaDate() throws Exception {
        // A skipped array element is written as null
        assertEquals("[null,1]", storedMapValue(jsValue("[new java.util.Date(0), 1]")));
    }

    /*
     * Arrays (NativeArray)
     */

    @Test
    public void testArraySerializesAsJsonString() throws Exception {
        // undefined, functions, and holes all become null
        Object value = jsValue("[1, 'two', {three: 3}, null, undefined, function() {}, , 8]");
        assertEquals(NativeArray.class, value.getClass());
        assertSerializesAsString("[1,\"two\",{\"three\":3},null,null,null,null,8]", value);
        assertEquals("[1,\"two\",{\"three\":3},null,null,null,null,8]", storedMapValue(value));
    }

    @Test
    public void testArrayContainingDateOutsideContext() throws Exception {
        // Same failure as an object containing a Date, but NativeArray.toString() is the Java identity string
        Object value = jsValue("[new Date(0)]");
        assertSerializeFails(value);
        assertTrue(((String) storedMapValue(value)).startsWith("org.mozilla.javascript.NativeArray@"));
    }

    @Test
    public void testArrayContainingDateOutsideContextAfterToJSONInitialized() throws Exception {
        jsValue("Date.prototype.toJSON");
        assertEquals("[\"1970-01-01T00:00:00.000Z\"]", storedMapValue(jsValue("[new Date(0)]")));
    }

    @Test
    public void testArrayContainingXml() throws Exception {
        Object value = jsValue("[new XML('<a1>t</a1>')]");
        assertSerializeFails(value);
        assertTrue(((String) storedMapValue(value)).startsWith("org.mozilla.javascript.NativeArray@"));
        inContext(() -> {
            assertSerializeFails(value);
            return null;
        });
    }

    @Test
    public void testArrayContainingDateInsideContext() throws Exception {
        Object value = jsValue("[new Date(0)]");
        assertEquals("[\"1970-01-01T00:00:00.000Z\"]", inContext(() -> storedMapValue(value)));
    }

    /*
     * Dates (NativeDate). Written as an unquoted ISO-8601 UTC string regardless of Context.
     */

    @Test
    public void testDateSerializesAsIsoString() throws Exception {
        Object value = jsValue("new Date(Date.UTC(2024, 0, 2, 3, 4, 5, 6))");
        assertSerializesAsString("2024-01-02T03:04:05.006Z", value);
        assertEquals("2024-01-02T03:04:05.006Z", storedMapValue(value));
        assertEquals("2024-01-02T03:04:05.006Z", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testInvalidDate() throws Exception {
        // In JavaScript, toISOString() throws a RangeError for an invalid date, but the converter calls
        // NativeDate.js_toISOString directly, which formats NaN as year zero
        Object value = jsValue("new Date(NaN)");
        assertSerializesAsString("0000-01-01T00:00:00.000Z", value);
        assertEquals("0000-01-01T00:00:00.000Z", storedMapValue(value));
    }

    /*
     * Functions. Not handled by JavaScriptObjectConverter, so they fall through to XStream
     * reflection, which fails on the sealed shared scope. MapUtil stores the Java identity string.
     */

    @Test
    public void testFunction() throws Exception {
        assertNotSerializableStoredAs("org.mozilla.javascript.InterpretedFunction@", jsValue("function foo(a) { return a; }"));
    }

    @Test
    public void testArrowFunction() throws Exception {
        assertNotSerializableStoredAs("org.mozilla.javascript.ArrowFunction@", jsValue("(a) => a"));
    }

    @Test
    public void testBoundFunction() throws Exception {
        assertNotSerializableStoredAs("org.mozilla.javascript.BoundFunction@", jsValue("(function() {}).bind(null)"));
    }

    @Test
    public void testJavaClassReference() throws Exception {
        // A NativeJavaClass is passed to Java as the Class itself, which XStream handles natively
        Object value = jsValue("java.lang.String");
        assertEquals(String.class, value);
        assertEquals("<java-class>java.lang.String</java-class>", serialize(value));
        assertEquals(String.class, storedMapValue(value));
    }

    /*
     * Regular expressions (NativeRegExp). Fail to serialize; stored as the source with flags.
     */

    @Test
    public void testRegExpLiteral() throws Exception {
        Object value = jsValue("/ab+c/gi");
        assertSerializeFails(value);
        assertEquals("/ab+c/gi", storedMapValue(value));
    }

    @Test
    public void testRegExpConstructor() throws Exception {
        Object value = jsValue("new RegExp('x', 'm')");
        assertSerializeFails(value);
        assertEquals("/x/m", storedMapValue(value));
    }

    /*
     * E4X (XML, XMLList, Namespace, QName). Fail to serialize; stored as the E4X toString(), which
     * for XML with simple content is just the text and not the markup.
     *
     * Note: Rhino throws StringIndexOutOfBoundsException when parsing XML whose text is shorter than
     * "<?xml" (e.g. the literal <a/>), so these tests use longer element names.
     */

    @Test
    public void testXmlLiteral() throws Exception {
        Object value = jsValue("<root attr=\"c\"><child>e</child></root>");
        assertEquals("org.mozilla.javascript.xmlimpl.XML", value.getClass().getName());
        assertSerializeFails(value);
        assertEquals("<root attr=\"c\"><child>e</child></root>", storedMapValue(value));
    }

    @Test
    public void testXmlMessage() throws Exception {
        // e.g. channelMap.put('msg', msg)
        Object value = jsValue("new XML('<HL7Message><MSH><MSH.1>|</MSH.1></MSH></HL7Message>')");
        assertSerializeFails(value);
        assertEquals("<HL7Message><MSH><MSH.1>|</MSH.1></MSH></HL7Message>", storedMapValue(value));
    }

    @Test
    public void testXmlWithSimpleContentLosesMarkup() throws Exception {
        // e.g. channelMap.put('field', msg['PID']['PID.5']['PID.5.1'])
        Object value = jsValue("new XML('<root>text</root>')");
        assertSerializeFails(value);
        assertEquals("text", storedMapValue(value));
    }

    @Test
    public void testXmlList() throws Exception {
        Object value = jsValue("<><one/><two/></>");
        assertEquals("org.mozilla.javascript.xmlimpl.XMLList", value.getClass().getName());
        assertSerializeFails(value);
        assertEquals("<one/><two/>", storedMapValue(value));
    }

    @Test
    public void testEmptyXmlList() throws Exception {
        // e.g. a query for a segment that doesn't exist
        Object value = jsValue("new XMLList()");
        assertSerializeFails(value);
        assertEquals("", storedMapValue(value));
    }

    @Test
    public void testNamespace() throws Exception {
        Object value = jsValue("new Namespace('p', 'urn:x')");
        assertSerializeFails(value);
        assertEquals("urn:x", storedMapValue(value));
    }

    @Test
    public void testQName() throws Exception {
        Object value = jsValue("new QName('urn:x', 'y')");
        assertSerializeFails(value);
        assertEquals("urn:x::y", storedMapValue(value));
    }

    /*
     * Primitive wrapper objects. Only NativeBoolean is handled by JavaScriptObjectConverter.
     */

    @Test
    public void testBooleanObject() throws Exception {
        assertSerializesAsString("false", jsValue("new Boolean(false)"));
        assertEquals("false", storedMapValue(jsValue("new Boolean(false)")));
        assertEquals("true", storedMapValue(jsValue("new Boolean(true)")));
    }

    @Test
    public void testStringObject() throws Exception {
        Object value = jsValue("new String('s')");
        assertEquals("org.mozilla.javascript.NativeString", value.getClass().getName());
        assertSerializeFails(value);
        assertEquals("s", storedMapValue(value));
    }

    @Test
    public void testNumberObject() throws Exception {
        Object value = jsValue("new Number(2)");
        assertEquals("org.mozilla.javascript.NativeNumber", value.getClass().getName());
        assertSerializeFails(value);
        // Stored as a String, unlike a number primitive which is stored as a Double
        assertEquals("2", storedMapValue(value));
    }

    @Test
    public void testSymbol() throws Exception {
        Object value = jsValue("Symbol('s')");
        assertSerializeFails(value);
        assertEquals("Symbol(s)", storedMapValue(value));
    }

    /*
     * Other built-ins. Fail to serialize. Errors keep a useful toString(), but the rest are stored
     * as Java identity strings and their contents are lost.
     */

    @Test
    public void testError() throws Exception {
        Object value = jsValue("new Error('boom')");
        assertSerializeFails(value);
        assertEquals("Error: boom", storedMapValue(value));
        assertEquals("TypeError: bad", storedMapValue(jsValue("new TypeError('bad')")));
    }

    @Test
    public void testMap() throws Exception {
        assertNotSerializableStoredAs("org.mozilla.javascript.NativeMap@", jsValue("new Map([['k', 1]])"));
    }

    @Test
    public void testSet() throws Exception {
        assertNotSerializableStoredAs("org.mozilla.javascript.NativeSet@", jsValue("new Set([1, 2])"));
    }

    @Test
    public void testTypedArray() throws Exception {
        assertNotSerializableStoredAs("org.mozilla.javascript.typedarrays.NativeUint8Array@", jsValue("new Uint8Array([1, 2, 3])"));
    }

    @Test
    public void testArguments() throws Exception {
        assertNotSerializableStoredAs("org.mozilla.javascript.Arguments@", jsValue("(function() { return arguments; })(1, 2)"));
    }

    /*
     * Java objects created by the script are unwrapped from NativeJavaObject before reaching the
     * map, so XStream sees the Java object. Scriptables nested inside Java collections still go
     * through JavaScriptObjectConverter.
     */

    @Test
    public void testJavaObject() throws Exception {
        Object value = jsValue("java.lang.Integer.valueOf(5)");
        assertEquals(Integer.valueOf(5), value);
        assertEquals(Integer.valueOf(5), storedMapValue(value));
    }

    @Test
    public void testJavaListContainingObject() throws Exception {
        Object value = jsValue("new java.util.ArrayList([{a: 1}])");
        assertEquals(ArrayList.class, value.getClass());
        assertEquals("<list>\n  <string>{&quot;a&quot;:1}</string>\n</list>", serialize(value));
        assertEquals(List.of("{\"a\":1}"), storedMapValue(value));
    }

    @Test
    public void testJavaListContainingDate() throws Exception {
        Object value = jsValue("new java.util.ArrayList([new Date(0)])");
        assertEquals("<list>\n  <string>1970-01-01T00:00:00.000Z</string>\n</list>", serialize(value));
        assertEquals(List.of("1970-01-01T00:00:00.000Z"), storedMapValue(value));
    }

    @Test
    public void testJavaListContainingFunction() throws Exception {
        // The whole Java list fails, so its Java toString() is stored
        Object value = jsValue("new java.util.ArrayList([function() {}])");
        assertSerializeFails(value);
        assertTrue(((String) storedMapValue(value)).startsWith("[org.mozilla.javascript.InterpretedFunction@"));
    }

    @Test
    public void testJavaListContainingXml() throws Exception {
        // e.g. list.add(msg['OBX'][i]['OBX.5']) for each OBX, then channelMap.put('values', list)
        // The whole Java list fails, so its Java toString() is stored as a String, not a List
        Object value = jsValue("(function() { var l = new java.util.ArrayList(); l.add(new XML('<a1>x</a1>')); l.add(new XML('<a1>y</a1>')); return l; })()");
        assertSerializeFails(value);
        assertEquals("[x, y]", storedMapValue(value));
    }

    @Test
    public void testJavaMapContainingObject() throws Exception {
        Object value = jsValue("java.util.Collections.singletonMap('k', {a: 1})");
        assertEquals(Map.of("k", "{\"a\":1}"), storedMapValue(value));
    }

    @Test
    public void testJavaMapContainingFunction() throws Exception {
        // The whole Java map fails, so its Java toString() is stored
        Object value = jsValue("java.util.Collections.singletonMap('k', function() {})");
        assertSerializeFails(value);
        assertTrue(((String) storedMapValue(value)).startsWith("{k=org.mozilla.javascript.InterpretedFunction@"));
    }

    /*
     * Whole map behavior
     */

    @Test
    public void testUnserializableValueDoesNotAffectOtherMapValues() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("object", jsValue("({a: 1})"));
        map.put("function", jsValue("function foo() {}"));
        map.put("number", jsValue("1"));
        map.put("xml", jsValue("<root>text</root>"));

        Map<String, Object> stored = roundTripMap(map);

        assertEquals(4, stored.size());
        assertEquals("{\"a\":1}", stored.get("object"));
        assertTrue(((String) stored.get("function")).startsWith("org.mozilla.javascript.InterpretedFunction@"));
        assertEquals(Double.valueOf(1), stored.get("number"));
        assertEquals("text", stored.get("xml"));
    }

    /*
     * Helpers
     */

    private static Context enterContext() {
        Context context = new ContextFactory().enterContext();
        context.setLanguageVersion(Context.VERSION_ES6);
        // rhino.optimizationlevel defaults to -1 (interpreted); class names such as InterpretedFunction depend on it
        context.setOptimizationLevel(-1);
        return context;
    }

    /// Evaluates the expression in a new script scope holding an E4X `msg`, passes the result to Java
    /// through `channelMap.put`, and returns what was stored. No Context is entered on return.
    private Object jsValue(String expression) {
        return jsValueInScope("msg = new XML('<HL7Message><MSH><MSH.1>|</MSH.1></MSH></HL7Message>');", expression);
    }

    /// Like [#jsValue(String)], but first runs scopeScript in the script scope instead of setting up
    /// msg.
    private Object jsValueInScope(String scopeScript, String expression) {
        Map<String, Object> channelMap = new HashMap<>();
        Context context = enterContext();
        try {
            // Mirrors JavaScriptScopeUtil.getScope
            Scriptable scope = context.newObject(sealedSharedScope);
            scope.setPrototype(sealedSharedScope);
            scope.setParentScope(null);
            ScriptableObject.putProperty(scope, "channelMap", Context.javaToJS(channelMap, scope));

            context.evaluateString(scope, scopeScript + "\nchannelMap.put('value', " + expression + ");", "test", 1, null);
        } finally {
            Context.exit();
        }
        return channelMap.get("value");
    }

    /// Runs the callable with a Context entered on the current thread.
    private static <T> T inContext(Callable<T> callable) throws Exception {
        enterContext();
        try {
            return callable.call();
        } finally {
            Context.exit();
        }
    }

    private static String serialize(Object value) {
        return ObjectXMLSerializer.getInstance().serialize(value);
    }

    private static Map<String, Object> roundTripMap(Map<String, Object> map) {
        String serialized = MapUtil.serializeMap(ObjectXMLSerializer.getInstance(), map);
        return MapUtil.deserializeMap(ObjectXMLSerializer.getInstance(), serialized);
    }

    /// Stores the value in a map the way JdbcDao does and returns the value read back.
    private static Object storedMapValue(Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put("value", value);
        return roundTripMap(map).get("value");
    }

    private static void assertSerializesAsString(String expected, Object value) {
        String xml = serialize(value);
        assertTrue(xml, xml.startsWith("<string>"));
        assertEquals(expected, ObjectXMLSerializer.getInstance().deserialize(xml, Object.class));
    }

    private static void assertSerializeFails(Object value) {
        try {
            String xml = serialize(value);
            fail("Expected serialization of " + value.getClass().getName() + " to fail, but got: " + xml.substring(0, Math.min(xml.length(), 200)));
        } catch (SerializerException e) {
            // expected
        }
    }

    private static void assertNotSerializableStoredAs(String expectedPrefix, Object value) {
        assertSerializeFails(value);
        Object stored = storedMapValue(value);
        assertTrue(String.valueOf(stored), stored instanceof String && ((String) stored).startsWith(expectedPrefix));
    }
}
