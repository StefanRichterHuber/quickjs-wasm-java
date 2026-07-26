package io.github.stefanrichterhuber.quickjswasmjava;

/**
 * Represents a function that accepts 0 to many arguments in the form of Objects
 * and produces a result
 * 
 * @param <R> the type of the result of the function
 */
@FunctionalInterface
public interface VarArgFunction<R> {
    /**
     * Applies this function to the given argument.
     *
     * @param t the function arguments
     * @return the function result
     */
    R apply(Object... t);
}
