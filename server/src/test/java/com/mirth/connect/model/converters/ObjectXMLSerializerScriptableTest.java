// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Tony Germano <tony@germano.name>

package com.mirth.connect.model.converters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

/// Characterization tests documenting how [ObjectXMLSerializer] handles the values a user script
/// can put into the channel map, global map, response map, etc. These assertions record current
/// behavior, including behavior that is lossy, so that changes to [JavaScriptObjectConverter] (or
/// anything else in the serialization path) are made deliberately. When a change here is intended,
/// update the assertion and its comment.
///
/// JavaScriptObjectConverter handles every Rhino object except wrapped Java objects. It writes plain
/// objects and arrays as JSON, valid dates as ISO-8601, and everything else as its JavaScript string
/// value, so these values always deserialize as java.lang.String.
///
/// Values are produced the way they are on a running server:
///
/// - The script runs in the interpreter at language version ES6 in a per-script scope whose
///   prototype is a sealed shared scope initialized like
///   `JavaScriptBuilder.generateGlobalSealedScript()`. That matters: once E4X is initialized or the
///   shared scope is sealed, Rhino objects that fall through to XStream reflection can no longer be
///   serialized at all.
/// - The per-script scope holds an E4X `msg`, as filter and transformer scopes do for XML-based
///   data types.
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
/// `router.routeMessage()` and the downstream channel stores its maps on the same thread. The
/// converter always runs on its own thread with its own Context, so the results are the same; tests
/// ending in `OutsideContext` and `InsideContext` check both for the types that used to differ.
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
     * A new shared scope per test, because Rhino lazily initializes built-in prototype methods, so
     * tests sharing a scope could depend on what ran before.
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
     * Objects that JSON.stringify can't handle are written as their string value.
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
        // Previously failed, because Date.prototype.toJSON needed a Context, and was stored as "[object Object]"
        Object value = jsValue("({d: new Date(0)})");
        assertSerializesAsString("{\"d\":\"1970-01-01T00:00:00.000Z\"}", value);
        assertEquals("{\"d\":\"1970-01-01T00:00:00.000Z\"}", storedMapValue(value));
    }

    @Test
    public void testObjectContainingDateInsideContext() throws Exception {
        Object value = jsValue("({d: new Date(0)})");
        assertEquals("{\"d\":\"1970-01-01T00:00:00.000Z\"}", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testObjectWithGetterOutsideContext() throws Exception {
        // Previously failed and was stored as "[object Object]"
        assertEquals("{\"g\":1}", storedMapValue(jsValue("({get g() { return 1; }})")));
    }

    @Test
    public void testObjectWithGetterInsideContext() throws Exception {
        Object value = jsValue("({get g() { return 1; }})");
        assertEquals("{\"g\":1}", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testObjectContainingPrimitiveWrappersOutsideContext() throws Exception {
        // Previously failed and was stored as "[object Object]"
        assertEquals("{\"n\":1,\"s\":\"s\"}", storedMapValue(jsValue("({n: new Number(1), s: new String('s')})")));
    }

    @Test
    public void testObjectContainingPrimitiveWrappersInsideContext() throws Exception {
        Object value = jsValue("({n: new Number(1), s: new String('s')})");
        assertEquals("{\"n\":1,\"s\":\"s\"}", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testCyclicObjectOutsideContext() throws Exception {
        // JSON.stringify throws on the cycle. Previously the serializer failed; MapUtil stored the same string.
        Object value = jsValue("(function() { var o = {}; o.self = o; return o; })()");
        assertSerializesAsString("[object Object]", value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testCyclicObjectInsideContext() throws Exception {
        Object value = jsValue("(function() { var o = {}; o.self = o; return o; })()");
        assertEquals("[object Object]", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testCyclicObjectDoesNotWriteScopeVariables() throws Exception {
        /*
         * Previously, inside a Context and without E4X in the per-script scope, the converter fell
         * back to reflection and wrote the per-script scope, including its top-level variables.
         */
        Object value = jsValueInScope("var topVar = 'v'; msg = 'raw';", "(function() { var o = {}; o.self = o; return o; })()");
        String xml = inContext(() -> serialize(value));
        assertEquals("<string>[object Object]</string>", xml);
    }

    @Test
    public void testObjectContainingXmlOutsideContext() throws Exception {
        // e.g. channelMap.put('patient', {name: msg['PID']['PID.5']})
        // JSON.stringify throws on the XML value. Previously the serializer failed; MapUtil stored the same string.
        Object value = jsValue("({x: new XML('<a1><b>1</b></a1>')})");
        assertSerializesAsString("[object Object]", value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testObjectContainingXmlInsideContext() throws Exception {
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
        // Previously failed, because calling a Java method from toJSON needs a Context, and was stored as "[object Object]"
        assertEquals("{\"0\":1}", storedMapValue(jsValue("({toJSON: function() { return java.util.Arrays.asList(1); }})")));
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
        // Previously failed and was stored as the Java identity string "org.mozilla.javascript.NativeArray@..."
        assertEquals("[\"1970-01-01T00:00:00.000Z\"]", storedMapValue(jsValue("[new Date(0)]")));
    }

    @Test
    public void testArrayContainingXml() throws Exception {
        /*
         * JSON.stringify throws on the XML value, so the array's string value is written, which
         * joins the elements' string values. Previously failed and was stored as the Java identity
         * string "org.mozilla.javascript.NativeArray@...".
         */
        Object value = jsValue("[new XML('<a1>t</a1>'), 2]");
        assertSerializesAsString("t,2", value);
        assertEquals("t,2", inContext(() -> storedMapValue(value)));
    }

    @Test
    public void testArrayContainingDateInsideContext() throws Exception {
        Object value = jsValue("[new Date(0)]");
        assertEquals("[\"1970-01-01T00:00:00.000Z\"]", inContext(() -> storedMapValue(value)));
    }

    /*
     * Dates (NativeDate). Written as an unquoted ISO-8601 UTC string.
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
        // toISOString() throws a RangeError for an invalid date, so its string value is written.
        // Previously NativeDate.js_toISOString was called directly, which formatted NaN as "0000-01-01T00:00:00.000Z".
        Object value = jsValue("new Date(NaN)");
        assertSerializesAsString("Invalid Date", value);
        assertEquals("Invalid Date", storedMapValue(value));
    }

    /*
     * Functions. Written as their source text. Rhino 1.7.13 decompiles it, so the whitespace differs
     * from the script, and newer versions keep the original text, so only the start is checked.
     * Previously they fell through to XStream reflection, which failed on the sealed shared scope,
     * and MapUtil stored the Java identity string (e.g. "org.mozilla.javascript.InterpretedFunction@...").
     */

    @Test
    public void testFunction() throws Exception {
        Object value = jsValue("function foo(a) { return a; }");
        assertFunctionSource("function foo(a)", ObjectXMLSerializer.getInstance().deserialize(serialize(value), Object.class));
        assertFunctionSource("function foo(a)", storedMapValue(value));
    }

    @Test
    public void testArrowFunction() throws Exception {
        assertFunctionSource("(a) => a", storedMapValue(jsValue("(a) => a")));
    }

    @Test
    public void testBoundFunction() throws Exception {
        Object stored = storedMapValue(jsValue("(function() {}).bind(null)"));
        assertFunctionSource("function", stored);
        assertTrue(String.valueOf(stored), ((String) stored).contains("[native code"));
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
     * Regular expressions (NativeRegExp). Written as the source with flags. Previously serialization
     * failed and MapUtil stored the same string.
     */

    @Test
    public void testRegExpLiteral() throws Exception {
        Object value = jsValue("/ab+c/gi");
        assertSerializesAsString("/ab+c/gi", value);
        assertEquals("/ab+c/gi", storedMapValue(value));
    }

    @Test
    public void testRegExpConstructor() throws Exception {
        Object value = jsValue("new RegExp('x', 'm')");
        assertSerializesAsString("/x/m", value);
        assertEquals("/x/m", storedMapValue(value));
    }

    /*
     * E4X (XML, XMLList, Namespace, QName). Written as the E4X toString(), which for XML with simple
     * content is just the text and not the markup. Previously serialization failed and MapUtil
     * stored the same string.
     *
     * Note: Rhino throws StringIndexOutOfBoundsException when parsing XML whose text is shorter than
     * "<?xml" (e.g. the literal <a/>), so these tests use longer element names.
     */

    @Test
    public void testXmlLiteral() throws Exception {
        Object value = jsValue("<root attr=\"c\"><child>e</child></root>");
        assertEquals("org.mozilla.javascript.xmlimpl.XML", value.getClass().getName());
        assertSerializesAsString("<root attr=\"c\"><child>e</child></root>", value);
        assertEquals("<root attr=\"c\"><child>e</child></root>", storedMapValue(value));
    }

    @Test
    public void testXmlMessage() throws Exception {
        // e.g. channelMap.put('msg', msg)
        Object value = jsValue("new XML('<HL7Message><MSH><MSH.1>|</MSH.1></MSH></HL7Message>')");
        assertSerializesAsString("<HL7Message><MSH><MSH.1>|</MSH.1></MSH></HL7Message>", value);
        assertEquals("<HL7Message><MSH><MSH.1>|</MSH.1></MSH></HL7Message>", storedMapValue(value));
    }

    @Test
    public void testXmlWithSimpleContentLosesMarkup() throws Exception {
        // e.g. channelMap.put('field', msg['PID']['PID.5']['PID.5.1'])
        Object value = jsValue("new XML('<root>text</root>')");
        assertSerializesAsString("text", value);
        assertEquals("text", storedMapValue(value));
    }

    @Test
    public void testXmlList() throws Exception {
        Object value = jsValue("<><one/><two/></>");
        assertEquals("org.mozilla.javascript.xmlimpl.XMLList", value.getClass().getName());
        assertSerializesAsString("<one/><two/>", value);
        assertEquals("<one/><two/>", storedMapValue(value));
    }

    @Test
    public void testEmptyXmlList() throws Exception {
        // e.g. a query for a segment that doesn't exist
        Object value = jsValue("new XMLList()");
        assertSerializesAsString("", value);
        assertEquals("", storedMapValue(value));
    }

    @Test
    public void testNamespace() throws Exception {
        Object value = jsValue("new Namespace('p', 'urn:x')");
        assertSerializesAsString("urn:x", value);
        assertEquals("urn:x", storedMapValue(value));
    }

    @Test
    public void testQName() throws Exception {
        Object value = jsValue("new QName('urn:x', 'y')");
        assertSerializesAsString("urn:x::y", value);
        assertEquals("urn:x::y", storedMapValue(value));
    }

    /*
     * Primitive wrapper objects. Written as their string value, as they are stored; previously only
     * NativeBoolean was handled by JavaScriptObjectConverter, and the others failed to serialize and
     * MapUtil stored the same string.
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
        assertSerializesAsString("s", value);
        assertEquals("s", storedMapValue(value));
    }

    @Test
    public void testNumberObject() throws Exception {
        Object value = jsValue("new Number(2)");
        assertEquals("org.mozilla.javascript.NativeNumber", value.getClass().getName());
        assertSerializesAsString("2", value);
        // Stored as a String, unlike a number primitive which is stored as a Double
        assertEquals("2", storedMapValue(value));
    }

    @Test
    public void testSymbol() throws Exception {
        Object value = jsValue("Symbol('s')");
        assertSerializesAsString("Symbol(s)", value);
        assertEquals("Symbol(s)", storedMapValue(value));
    }

    /*
     * Other built-ins. Written as their JavaScript string value. Errors keep their message; the
     * contents of the others are lost. Previously serialization failed, and MapUtil stored the same
     * string for errors and the Java identity string (e.g. "org.mozilla.javascript.NativeMap@...")
     * for the rest.
     */

    @Test
    public void testError() throws Exception {
        Object value = jsValue("new Error('boom')");
        assertSerializesAsString("Error: boom", value);
        assertEquals("Error: boom", storedMapValue(value));
        assertEquals("TypeError: bad", storedMapValue(jsValue("new TypeError('bad')")));
    }

    @Test
    public void testMap() throws Exception {
        assertEquals("[object Map]", storedMapValue(jsValue("new Map([['k', 1]])")));
    }

    @Test
    public void testSet() throws Exception {
        assertEquals("[object Set]", storedMapValue(jsValue("new Set([1, 2])")));
    }

    @Test
    public void testTypedArray() throws Exception {
        assertEquals("1,2,3", storedMapValue(jsValue("new Uint8Array([1, 2, 3])")));
    }

    @Test
    public void testArguments() throws Exception {
        assertEquals("[object Arguments]", storedMapValue(jsValue("(function() { return arguments; })(1, 2)")));
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
        // Previously the whole Java list failed, and its Java toString() was stored as a String
        Object stored = storedMapValue(jsValue("new java.util.ArrayList([function() {}])"));
        assertEquals(ArrayList.class, stored.getClass());
        assertEquals(1, ((ArrayList<?>) stored).size());
        assertFunctionSource("function ()", ((ArrayList<?>) stored).get(0));
    }

    @Test
    public void testJavaListContainingXml() throws Exception {
        // e.g. list.add(msg['OBX'][i]['OBX.5']) for each OBX, then channelMap.put('values', list)
        // Previously the whole Java list failed, and its Java toString() "[x, y]" was stored as a String, not a List
        Object value = jsValue("(function() { var l = new java.util.ArrayList(); l.add(new XML('<a1>x</a1>')); l.add(new XML('<a1>y</a1>')); return l; })()");
        assertEquals(List.of("x", "y"), storedMapValue(value));
    }

    @Test
    public void testJavaMapContainingObject() throws Exception {
        Object value = jsValue("java.util.Collections.singletonMap('k', {a: 1})");
        assertEquals(Map.of("k", "{\"a\":1}"), storedMapValue(value));
    }

    @Test
    public void testJavaMapContainingFunction() throws Exception {
        // Previously the whole Java map failed, and its Java toString() was stored as a String
        Object stored = storedMapValue(jsValue("java.util.Collections.singletonMap('k', function() {})"));
        assertTrue(String.valueOf(stored), stored instanceof Map);
        assertFunctionSource("function ()", ((Map<?, ?>) stored).get("k"));
    }

    /*
     * Whole map behavior
     */

    @Test
    public void testUnserializableValueDoesNotAffectOtherMapValues() throws Exception {
        // A JavaScript value no longer fails, so use a Java object XStream can't write
        Map<String, Object> map = new HashMap<>();
        map.put("object", jsValue("({a: 1})"));
        map.put("unserializable", new Unserializable());
        map.put("number", jsValue("1"));
        map.put("xml", jsValue("<root>text</root>"));

        Map<String, Object> stored = roundTripMap(map);

        assertEquals(4, stored.size());
        assertEquals("{\"a\":1}", stored.get("object"));
        assertEquals("unserializable", stored.get("unserializable"));
        assertEquals(Double.valueOf(1), stored.get("number"));
        assertEquals("text", stored.get("xml"));
    }

    /*
     * Edge cases of the conversion itself
     */

    @Test
    public void testScriptGlobalsDoNotAffectConversion() throws Exception {
        // The conversion runs in its own scope, so a script's top-level declarations can't replace JSON, String, etc.
        String scopeScript = """
                var JSON = {stringify: function() { return 'replaced'; }};
                function String() { return 'replaced'; }
                var isNaN = function() { return true; };
                var Object = 5;
                """;
        assertEquals("{\"a\":1}", storedMapValue(jsValueInScope(scopeScript, "({a: 1})")));
        assertEquals("/x/", storedMapValue(jsValueInScope(scopeScript, "/x/")));
        assertEquals("1970-01-01T00:00:00.000Z", storedMapValue(jsValueInScope(scopeScript, "new Date(0)")));
    }

    @Test
    public void testObjectWithoutScope() throws Exception {
        // e.g. created in Java by a plugin or library
        NativeObject object = new NativeObject();
        object.put("a", object, 1);
        assertEquals("{\"a\":1}", storedMapValue(object));
    }

    @Test
    public void testObjectThatCannotBeConverted() throws Exception {
        // JSON.stringify and String() both throw, so serialization fails and MapUtil stores the Java toString()
        Object value = jsValue("({toJSON: function() { throw 'json'; }, toString: function() { throw 'string'; }})");
        assertSerializeFails(value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testObjectWithDatePrototype() throws Exception {
        // Not a real Date, so getTime() and Date.prototype.toString() both throw TypeError
        Object value = jsValue("Object.create(Date.prototype)");
        assertSerializeFails(value);
        assertEquals("[object Object]", storedMapValue(value));
    }

    @Test
    public void testConcurrentConversion() throws Exception {
        List<Object> values = List.of(jsValue("({a: 1, d: new Date(0)})"), jsValue("[1, 'x']"), jsValue("new Date(0)"), jsValue("/x/g"), jsValue("<root>text</root>"));
        List<Object> expected = List.of("{\"a\":1,\"d\":\"1970-01-01T00:00:00.000Z\"}", "[1,\"x\"]", "1970-01-01T00:00:00.000Z", "/x/g", "text");

        // Start from a new conversion scope, so the first conversions on several threads race with its creation
        JavaScriptObjectConverter.resetConversion();
        CyclicBarrier start = new CyclicBarrier(8);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<Object>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await(60, TimeUnit.SECONDS);
                    List<Object> results = new ArrayList<>();
                    for (int j = 0; j < 50; j++) {
                        for (Object value : values) {
                            results.add(storedMapValue(value));
                        }
                    }
                    return results;
                }));
            }
            for (Future<List<Object>> future : futures) {
                List<Object> results = future.get(60, TimeUnit.SECONDS);
                for (int i = 0; i < results.size(); i++) {
                    assertEquals(expected.get(i % expected.size()), results.get(i));
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testConversionIgnoresHaltedContext() throws Exception {
        // e.g. a script halted while router.routeMessage() stores a downstream channel's maps on the same thread
        Object value = jsValue("({a: 1})");
        ContextFactory haltedFactory = new ContextFactory() {
            @Override
            protected void observeInstructionCount(Context context, int instructionCount) {
                throw new Error("halted");
            }
        };
        Context context = haltedFactory.enterContext();
        try {
            context.setOptimizationLevel(-1);
            context.setInstructionObserverThreshold(1);
            assertEquals("{\"a\":1}", storedMapValue(value));
        } finally {
            Context.exit();
        }
    }

    @Test(timeout = 30000)
    public void testRunawayToJSONExceedsTimeLimit() throws Exception {
        // The time limit can't be caught in JavaScript, so String() isn't tried and MapUtil stores the Java toString()
        assertExceedsTimeLimit(jsValue("({toJSON: function() { while (true) {} }, toString: function() { return 'string'; }})"));
    }

    @Test(timeout = 30000)
    public void testRunawayGetterExceedsTimeLimit() throws Exception {
        // The finally block is cut off before its first statement
        StringBuilder log = new StringBuilder();
        assertExceedsTimeLimit(jsValueInScope("", "({get a() { try { while (true) {} } finally { log.append('finally'); while (true) {} } }})", -1, Map.of("log", log)));
        assertEquals("", log.toString());
    }

    @Test(timeout = 30000)
    public void testRunawayCompiledToJSONExceedsTimeLimit() throws Exception {
        // User code compiled by a MirthContext also counts instructions
        assertExceedsTimeLimit(jsValueInScope("", "({toJSON: function() { while (true) {} }})", 0, Map.of()));
    }

    @Test
    public void testJavaErrorDuringConversionPropagates() throws Exception {
        // Errors other than the time limit propagate as they did when conversions ran on the caller's thread
        Object value = jsValueInScope("", "({toJSON: function() { thrower.run(); }})", -1, Map.of("thrower", new ErrorThrower()));
        assertThrows(TestError.class, () -> storedMapValue(value));
    }

    @Test(timeout = 30000)
    public void testInterruptedCallerWaitsForConversion() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // The interrupt is passed on to the conversion, but this toJSON ignores it and keeps waiting
        String toJSON = "function() { started.countDown(); while (true) { try { release.await(); return 'done'; } catch (e) {} } }";
        Object value = jsValueInScope("", "({toJSON: " + toJSON + "})", -1, Map.of("started", started, "release", release));

        // Interrupt the caller while it waits for the conversion, then let the conversion finish
        Thread caller = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                if (started.await(10, TimeUnit.SECONDS)) {
                    caller.interrupt();
                }
            } catch (InterruptedException e) {
                // the test fails below if the caller wasn't interrupted
            } finally {
                release.countDown();
            }
        });
        interrupter.setDaemon(true);
        interrupter.start();
        try {
            assertEquals("\"done\"", storedMapValue(value));
            assertTrue("interrupt status should be kept", Thread.currentThread().isInterrupted());
        } finally {
            interrupter.join();
            Thread.interrupted();
        }
    }

    @Test(timeout = 30000)
    public void testInterruptReachesBlockedConversion() throws Exception {
        // e.g. a channel halted while toJSON is blocked in a Java call
        CountDownLatch started = new CountDownLatch(1);
        Object value = jsValueInScope("", "({toJSON: function() { started.countDown(); java.lang.Thread.sleep(600000); return 'done'; }})", -1, Map.of("started", started));

        Thread caller = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                if (started.await(10, TimeUnit.SECONDS)) {
                    caller.interrupt();
                }
            } catch (InterruptedException e) {
                // the test fails below if the caller wasn't interrupted
            }
        });
        interrupter.setDaemon(true);
        interrupter.start();
        try {
            // The InterruptedException is caught by the conversion function, which then uses String()
            assertEquals("[object Object]", storedMapValue(value));
            assertTrue("interrupt status should be kept", Thread.currentThread().isInterrupted());
        } finally {
            interrupter.join();
            Thread.interrupted();
        }
    }

    @Test(timeout = 30000)
    public void testNestedConversion() throws Exception {
        // User code called by a conversion can store a map itself, which needs another conversion thread
        Map<String, Object> variables = Map.of("serializer", ObjectXMLSerializer.getInstance());
        Object value = jsValueInScope("", "({toJSON: function() { return String(serializer.serialize({b: 1})); }})", -1, variables);
        assertEquals("\"<string>{&quot;b&quot;:1}</string>\"", storedMapValue(value));
    }

    @Test
    public void testConversionUsesCallerContextClassLoader() throws Exception {
        Object value = jsValue("({toJSON: function() { return String(java.lang.Thread.currentThread().getContextClassLoader()); }})");
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader marker = new URLClassLoader(new URL[0], original) {
            @Override
            public String toString() {
                return "marker";
            }
        }) {
            thread.setContextClassLoader(marker);
            assertEquals("\"marker\"", storedMapValue(value));

            // The conversion thread's own class loader is restored, so an idle thread doesn't keep the caller's reachable
            for (Thread conversionThread : Thread.getAllStackTraces().keySet()) {
                if (conversionThread.getName().startsWith("JavaScriptObjectConverter-")) {
                    assertNotSame(conversionThread.getName(), marker, conversionThread.getContextClassLoader());
                }
            }
        } finally {
            thread.setContextClassLoader(original);
        }
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
        return jsValueInScope(scopeScript, expression, -1, Map.of());
    }

    /// Like [#jsValueInScope(String, String)], but compiles the script at the given optimization
    /// level and first puts the variables into the script scope as Java objects.
    private Object jsValueInScope(String scopeScript, String expression, int optimizationLevel, Map<String, Object> variables) {
        Map<String, Object> channelMap = new HashMap<>();
        Context context = enterContext();
        try {
            context.setOptimizationLevel(optimizationLevel);
            // MirthContext always sets an instruction observer threshold, so compiled code counts instructions
            context.setInstructionObserverThreshold(1);

            // Mirrors JavaScriptScopeUtil.getScope
            Scriptable scope = context.newObject(sealedSharedScope);
            scope.setPrototype(sealedSharedScope);
            scope.setParentScope(null);
            ScriptableObject.putProperty(scope, "channelMap", Context.javaToJS(channelMap, scope));
            for (Map.Entry<String, Object> variable : variables.entrySet()) {
                ScriptableObject.putProperty(scope, variable.getKey(), Context.javaToJS(variable.getValue(), scope));
            }

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

    private void assertExceedsTimeLimit(Object value) {
        long original = JavaScriptObjectConverter.timeLimitMillis;
        JavaScriptObjectConverter.timeLimitMillis = 200;
        try {
            assertSerializeFails(value);
            assertEquals("[object Object]", storedMapValue(value));
        } finally {
            JavaScriptObjectConverter.timeLimitMillis = original;
        }
        // Later conversions are unaffected
        assertEquals("{\"a\":1}", storedMapValue(jsValue("({a: 1})")));
    }

    private static void assertFunctionSource(String expectedStart, Object stored) {
        assertTrue(String.valueOf(stored), stored instanceof String source && source.trim().startsWith(expectedStart));
    }

    public static class ErrorThrower {
        public void run() {
            throw new TestError();
        }
    }

    private static class TestError extends Error {}

    /// A Java object that XStream fails to write.
    private static class Unserializable implements Serializable {
        private void writeObject(ObjectOutputStream out) throws IOException {
            throw new IOException("not serializable");
        }

        @Override
        public String toString() {
            return "unserializable";
        }
    }
}
