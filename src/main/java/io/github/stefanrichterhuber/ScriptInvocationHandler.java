package io.github.stefanrichterhuber;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import io.github.stefanrichterhuber.quickjswasmjava.QuickJSContext;

public class ScriptInvocationHandler implements InvocationHandler {

    private final QuickJSContext context;

    public ScriptInvocationHandler(QuickJSContext context) {
        this.context = context;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
    }
}
