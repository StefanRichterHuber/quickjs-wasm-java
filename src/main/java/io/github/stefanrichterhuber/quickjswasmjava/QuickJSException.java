package io.github.stefanrichterhuber.quickjswasmjava;

/**
 * Exception thrown when a QuickJS script throws an exception. It contains the
 * message and the stack trace. If a java callback throws an exception, it is
 * wrapped into a QuickJSException. The original message is kept, but the Java
 * stacktrace will be replaced by the JS stacktrace.
 */
public class QuickJSException extends RuntimeException {
    /**
     * The stack trace of the exception.
     */
    private final String stack;
    /**
     * The raw message of the exception.
     */
    private final String message;

    /**
     * Creates a new QuickJSException.
     * 
     * @param message The message of the exception.
     * @param stack   The stack trace of the exception.
     */
    QuickJSException(String message, String stack) {
        this(message, stack, null);
    }

    /**
     * Creates a new QuickJSException.
     * 
     * @param message The message of the exception.
     * @param stack   The stack trace of the exception.
     * @param cause   The cause of the exception.
     */
    QuickJSException(String message, String stack, Throwable cause) {
        super(message, cause);
        this.stack = stack;
        this.message = message;
    }

    /**
     * Reeturns the string containing the JS exception stack
     * 
     * @return JS stack
     */
    public String getStack() {
        return this.stack;
    }

    /**
     * Returns the raw exception message from JS
     * 
     * @return raw message
     */
    public String getRawMessage() {
        return this.message;
    }
}
