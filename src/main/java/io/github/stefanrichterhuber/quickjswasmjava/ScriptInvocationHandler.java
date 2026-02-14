package io.github.stefanrichterhuber.quickjswasmjava;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Invocation handler for a QuickJS context. This allows to create a dynamic
 * proxy for the QuickJS context / a QuickJS object.
 */
class ScriptInvocationHandler<K, V> implements InvocationHandler {

    /**
     * The QuickJS context to use.
     */
    private final QuickJSContext context;

    /**
     * The QuickJS object to use. If null, the global context is used.
     */
    private final QuickJSObject<K, V> thiz;

    /**
     * Creates a new ScriptInvocationHandler. Delegates method invocations to the
     * QuickJS context.
     * 
     * @param context The context to use.
     * @param thiz    The thiz object to use.
     */
    public ScriptInvocationHandler(QuickJSContext context, QuickJSObject<K, V> thiz) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        this.context = context;
        this.thiz = thiz;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final String methodName = method.getName();

        if (thiz != null) {
            Object f = thiz.get(methodName);
            if (f instanceof QuickJSFunction) {
                return ((QuickJSFunction) f).call(args);
            } else {
                throw new NoSuchMethodException("Method " + methodName + " not found in object " + thiz);
            }
        }

        return this.context.invoke(methodName, args);
    }
}
