package io.github.stefanrichterhuber.quickjswasmjava;

/**
 * Exception thrown when a QuickJS script throws an exception.
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
        super(message);
        this.stack = stack;
        this.message = message;
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

    public String getStack() {
        return this.stack;
    }

    public String getRawMessage() {
        return this.message;
    }
}
