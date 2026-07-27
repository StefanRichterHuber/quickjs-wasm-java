package io.github.stefanrichterhuber.quickjswasmjava;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

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

    /**
     * Creates a new VarArgFunction with the first n args already applied
     * 
     * @param t0 First args to set
     * @return VarArgFunction
     * @throws NullPointerException if t0 is null
     */
    default VarArgFunction<R> withFirstArgs(Object... t0) {
        Objects.requireNonNull(t0);
        return withArgsMapped(t1 -> joinArgs(t0, t1));
    }

    /**
     * Creates a new VarArgFunction with the last n args already applied
     * 
     * @param t1 Last args to set
     * @return VarArgFunction
     * @throws NullPointerException if t1 is null
     */
    default VarArgFunction<R> withTrailingArgs(Object... t1) {
        Objects.requireNonNull(t1);
        return withArgsMapped(t0 -> joinArgs(t0, t1));
    }

    /**
     * Helper function to join two args array
     * 
     * @param t Arrays to joing
     * @return Array containing the content of all arrays
     */
    private Object[] joinArgs(Object[]... t) {
        if (t == null || t.length == 0) {
            return new Object[0];
        }
        final Object[] args = Arrays.stream(t).filter(Objects::nonNull).flatMap(Arrays::stream).toArray();
        return args;

    }

    /**
     * Creates a new VarArgFunction with the given UnaryOperator applied on the args
     * 
     * @param mapper UnaryOperator to apply on the args
     * @return new VarArgFunction
     * @throws NullPointerException if mapper is null
     */
    default VarArgFunction<R> withArgsMapped(UnaryOperator<Object[]> mapper) {
        Objects.requireNonNull(mapper);
        return t -> apply(mapper.apply(t));
    }

    /**
     * Returns a composed function that first applies this VarArgFunction to
     * its input, and then applies the {@code after} function to the result.
     * If evaluation of either function throws an exception, it is relayed to
     * the caller of the composed function.
     *
     * @param <V>   the type of output of the {@code after} function, and of the
     *              composed function
     * @param after the function to apply after this function is applied
     * @return a composed function that first applies this function and then
     *         applies the {@code after} function
     * @throws NullPointerException if after is null
     *
     */
    default <V> VarArgFunction<V> andThen(Function<? super R, ? extends V> after) {
        Objects.requireNonNull(after);
        return (Object[] t) -> after.apply(apply(t));
    }

    /**
     * An implemeantion of VarArgFunction just returning one constant value
     * 
     * @param <R>   Type of the output
     * @param value Value to return
     * @return VarArgFunction returning this value
     */
    static <R> VarArgFunction<R> constant(R value) {
        return t -> value;
    }
}
