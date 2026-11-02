/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.model.converters;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * How long a conversion may run JavaScript, including user code such as toJSON, getters and
     * toString, before it fails. User code is covered if it is interpreted, or compiled by a
     * Context with an instruction observer threshold, as MirthContext always sets. The limit is
     * only checked between JavaScript instructions, so a long Java call or built-in, such as a
     * regular expression that backtracks, isn't stopped until it returns.
     */
    static final long DEFAULT_TIME_LIMIT_MILLIS = 1000;

    // Not final so tests can shorten it
    static volatile long timeLimitMillis = DEFAULT_TIME_LIMIT_MILLIS;

    private static final ConversionContextFactory CONTEXT_FACTORY = new ConversionContextFactory();

    /*
     * Conversions run on their own threads, so they never use a Context the caller has entered,
     * which may be halted or configured differently. Daemon threads, because this class also
     * ships in mirth-client-core.jar and must not keep a JVM running. A cached pool, so callers
     * never wait for each other, even when user code called by a conversion stores a map itself.
     */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new DaemonThreadFactory());

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
        ConversionTask task = new ConversionTask((Scriptable) value, Thread.currentThread().getContextClassLoader(), TimeUnit.MILLISECONDS.toNanos(timeLimitMillis));
        Future<String> future = EXECUTOR.submit(task);

        String result;
        try {
            result = await(future, task);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error && !(error instanceof TimeLimitExceededError)) {
                // e.g. StackOverflowError, which propagated when conversions ran on the caller's thread
                if (Arrays.stream(error.getSuppressed()).noneMatch(RethrownException.class::isInstance)) {
                    // Only once, in case user code throws the same Error instance again
                    error.addSuppressed(new RethrownException());
                }
                throw error;
            }
            // Don't fall back to reflection, which would write the object's scope chain
            throw new ConversionException("Unable to convert JavaScript object of type " + value.getClass().getName(), cause);
        }

        writer.setValue(result);
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        // These objects are written as "string" elements, so this is only reached by XML that names a Rhino class
        throw new ConversionException("JavaScript objects are serialized as strings and cannot be deserialized as " + context.getRequiredType().getName());
    }

    /*
     * Runs a conversion on a conversion thread. User code called by the conversion sees the
     * caller's context class loader, as it would on the caller's thread. It is reset afterwards so
     * an idle thread doesn't keep a channel's class loader reachable.
     */
    private static class ConversionTask implements Callable<String> {
        private final Scriptable value;
        private final ClassLoader callerClassLoader;
        private final long timeLimitNanos;

        // Guarded by this: the thread running the conversion, while it runs, and whether the caller was interrupted
        private Thread thread;
        private boolean interrupted;

        ConversionTask(Scriptable value, ClassLoader callerClassLoader, long timeLimitNanos) {
            this.value = value;
            this.callerClassLoader = callerClassLoader;
            this.timeLimitNanos = timeLimitNanos;
        }

        @Override
        public String call() {
            Thread current = Thread.currentThread();
            ClassLoader threadClassLoader = current.getContextClassLoader();
            synchronized (this) {
                thread = current;
                if (interrupted) {
                    current.interrupt();
                }
            }
            current.setContextClassLoader(callerClassLoader);
            try {
                return CONTEXT_FACTORY.call(context -> {
                    context.putThreadLocal(ConversionContextFactory.DEADLINE_KEY, System.nanoTime() + timeLimitNanos);
                    return getConversion(context).convert(context, value);
                });
            } finally {
                current.setContextClassLoader(threadClassLoader);
                synchronized (this) {
                    thread = null;
                }
                // Clear an interrupt meant for this conversion, so it can't reach the thread's next task
                Thread.interrupted();
            }
        }

        synchronized void interrupt() {
            interrupted = true;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    /*
     * If the caller is interrupted, e.g. because its channel is being halted, the interrupt is
     * passed on to the conversion, so user code blocked in a Java call is interrupted as it would
     * be on the caller's thread. The caller still waits for the conversion to finish, because it
     * may still be using the caller's objects, and then restores its interrupt status.
     */
    private static <T> T await(Future<T> future, ConversionTask task) throws ExecutionException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    if (!interrupted) {
                        interrupted = true;
                        task.interrupt();
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
            scope = context.initSafeStandardObjects(null, true);
            function = context.compileFunction(scope, CONVERSION_FUNCTION, "JavaScriptObjectConverter", 1, null);

            // Rhino creates built-in objects and methods on first use; do that now rather than on several threads at once
            Scriptable warmUp = context.newObject(scope);
            warmUp.put("d", warmUp, context.newObject(scope, "Date", new Object[] { 0 }));
            warmUp.put("a", warmUp, context.newArray(scope, new Object[] { "x" }));
            convert(context, warmUp);
            convert(context, context.newObject(scope, "Error"));

            scope.sealObject();
        }

        String convert(Context context, Scriptable value) {
            return Context.toString(function.call(context, scope, scope, new Object[] { value }));
        }
    }

    /*
     * Creates the Contexts conversions run in. The instruction observer stops conversions that run
     * past their deadline. It is called for user code whether that code was interpreted or
     * compiled, because MirthContext always sets an observer threshold.
     *
     * The conversion function is interpreted: its time is spent in built-ins such as
     * JSON.stringify, so compiling it would only add a generated class. (ContextFactory's default
     * optimization level is 0, not -1.)
     */
    private static class ConversionContextFactory extends ContextFactory {
        static final Object DEADLINE_KEY = new Object();

        // How many interpreter instructions run between deadline checks
        private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10000;

        ConversionContextFactory() {
            initApplicationClassLoader(JavaScriptObjectConverter.class.getClassLoader());
        }

        @Override
        protected Context makeContext() {
            Context context = super.makeContext();
            context.setLanguageVersion(Context.VERSION_ES6);
            context.setOptimizationLevel(-1);
            context.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
            return context;
        }

        @Override
        protected void observeInstructionCount(Context context, int instructionCount) {
            if (context.getThreadLocal(DEADLINE_KEY) instanceof Long deadline && System.nanoTime() - deadline > 0) {
                // An Error, so JavaScript can't catch it, and a finally block is cut off at once
                throw new TimeLimitExceededError();
            }
        }
    }

    private static class RethrownException extends Exception {
        RethrownException() {
            super("Rethrown on thread " + Thread.currentThread().getName());
        }
    }

    private static class TimeLimitExceededError extends Error {
        TimeLimitExceededError() {
            super("JavaScript object conversion exceeded its time limit");
        }
    }

    private static class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            // Don't inherit the creating caller's inheritable thread-locals, which an idle thread would keep reachable
            Thread thread = new Thread(null, runnable, "JavaScriptObjectConverter-" + count.incrementAndGet(), 0, false);
            thread.setDaemon(true);
            thread.setContextClassLoader(JavaScriptObjectConverter.class.getClassLoader());
            return thread;
        }
    }
}
