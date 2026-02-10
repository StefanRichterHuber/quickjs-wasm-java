package io.github.stefanrichterhuber.quickjswasmjava;

public class QuickJSException extends RuntimeException {
    private final String stack;
    private final String message;

    public QuickJSException(String message, String stack) {
        super(message);
        this.stack = stack;
        this.message = message;
    }

    public QuickJSException(String message, String stack, Throwable cause) {
        super(message, cause);
        this.stack = stack;
        this.message = message;
    }

    public String getStack() {
        return this.stack;
    }

    public String getRawMessage() {
        return this.message;
    }
}
