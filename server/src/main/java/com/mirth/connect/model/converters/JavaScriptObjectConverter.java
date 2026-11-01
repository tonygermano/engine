/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.model.converters;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Wrapper;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

/**
 * Writes JavaScript objects as strings. MirthMapperWrapper names the element "string", so they
 * deserialize as java.lang.String.
 */
public class JavaScriptObjectConverter implements Converter {

    /*
     * The conversion is done in JavaScript so we don't depend on Rhino internals. Plain objects and
     * arrays are written as JSON, and valid dates as an unquoted ISO-8601 string. Everything else,
     * and objects that JSON.stringify can't handle (e.g. cycles, or E4X values inside them), is
     * written as its JavaScript string value.
     *
     * Types are detected by their class tag rather than instanceof, which works for objects from
     * any scope. Methods called on obj itself come from its own prototype chain.
     */
    private static final String CONVERSION_FUNCTION = """
            function(obj) {
                var type = Object.prototype.toString.call(obj);
                if (type == '[object Date]') {
                    try {
                        if (!isNaN(obj.getTime())) {
                            return obj.toISOString();
                        }
                    } catch (e) {
                    }
                } else if (type == '[object Object]' || type == '[object Array]') {
                    try {
                        var json = JSON.stringify(obj);
                        if (json !== undefined) {
                            return json;
                        }
                    } catch (e) {
                    }
                }
                return String(obj);
            }""";

    private static volatile Conversion conversion;

    public JavaScriptObjectConverter() {}

    /**
     * Returns true for JavaScript objects. Wrapped Java objects are excluded so that XStream keeps
     * handling them as before.
     */
    public static boolean isJavaScriptObject(Class<?> type) {
        return type != null && Scriptable.class.isAssignableFrom(type) && !Wrapper.class.isAssignableFrom(type);
    }

    @Override
    public boolean canConvert(Class type) {
        return isJavaScriptObject(type);
    }

    @Override
    public void marshal(Object value, HierarchicalStreamWriter writer, MarshallingContext marshalContext) {
        Context context = Context.getCurrentContext();
        boolean exitContext = false;
        if (context == null) {
            // On the server the global factory is a MirthContextFactory
            context = ContextFactory.getGlobal().enterContext();
            exitContext = true;
        }

        String result;
        try {
            result = getConversion(context).convert(context, (Scriptable) value);
        } catch (Exception e) {
            // Don't fall back to reflection, which would write the object's scope chain
            throw new ConversionException("Unable to convert JavaScript object of type " + value.getClass().getName(), e);
        } finally {
            if (exitContext) {
                Context.exit();
            }
        }

        writer.setValue(result);
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        // These objects are written as "string" elements, so this is only reached by XML that names a Rhino class
        throw new ConversionException("JavaScript objects are serialized as strings and cannot be deserialized as " + context.getRequiredType().getName());
    }

    private static Conversion getConversion(Context context) {
        Conversion result = conversion;
        if (result == null) {
            synchronized (JavaScriptObjectConverter.class) {
                result = conversion;
                if (result == null) {
                    result = new Conversion(context);
                    conversion = result;
                }
            }
        }
        return result;
    }

    // For tests: the next conversion creates a new conversion scope
    static void resetConversion() {
        conversion = null;
    }

    /*
     * The conversion function and the private scope it runs in. The scope has only the standard
     * objects and is sealed, so neither user scripts nor the conversion can change JSON, String,
     * etc., and objects with no scope of their own can still be converted.
     */
    private static class Conversion {
        private final ScriptableObject scope;
        private final Function function;

        Conversion(Context context) {
            /*
             * Compile for the interpreter, so no class is generated in the current factory's class
             * loader, which may belong to a single channel's resources.
             */
            int optimizationLevel = context.getOptimizationLevel();
            context.setOptimizationLevel(-1);
            try {
                scope = context.initSafeStandardObjects(null, true);
                function = context.compileFunction(scope, CONVERSION_FUNCTION, "JavaScriptObjectConverter", 1, null);

                // Rhino creates built-in objects and methods on first use; do that now rather than on several threads at once
                Scriptable warmUp = context.newObject(scope);
                warmUp.put("d", warmUp, context.newObject(scope, "Date", new Object[] { 0 }));
                warmUp.put("a", warmUp, context.newArray(scope, new Object[] { "x" }));
                convert(context, warmUp);
                convert(context, context.newObject(scope, "Error"));

                scope.sealObject();
            } finally {
                context.setOptimizationLevel(optimizationLevel);
            }
        }

        String convert(Context context, Scriptable value) {
            return Context.toString(function.call(context, scope, scope, new Object[] { value }));
        }
    }
}
