package io.github.stefanrichterhuber.quickjswasmjava;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

public class VarArgFunctionTest {

    @Test
    public void testVarArgFunction() {
        VarArgFunction<Integer> f1 = values -> {
            return Arrays.stream(values).map(f -> (Integer) f).mapToInt(i -> i.intValue()).sum();
        };

        assertEquals(3, f1.apply(1, 2));
        assertEquals(6, f1.apply(1, 2, 3));
        assertEquals(10, f1.apply(1, 2, 3, 4));
    }

    @Test
    public void testVarArgFunctionAndThen() {
        VarArgFunction<Integer> f1 = values -> {
            return Arrays.stream(values).map(f -> (Integer) f).mapToInt(i -> i.intValue()).sum();
        };

        VarArgFunction<Integer> f2 = f1.andThen(v -> v * 2);

        assertEquals(3 * 2, f2.apply(1, 2));
        assertEquals(6 * 2, f2.apply(1, 2, 3));
        assertEquals(10 * 2, f2.apply(1, 2, 3, 4));
    }

    @Test
    public void testWithFirstArgs() {
        // arg 1 ... n is summed up and diveded by the first arg
        VarArgFunction<Integer> f1 = values -> {
            return Arrays.stream(values, 1, values.length).map(f -> (Integer) f).mapToInt(i -> i.intValue())
                    .sum() / (Integer) values[0];
        };
        VarArgFunction<Integer> f2 = f1.withFirstArgs(2);

        assertEquals(4, f2.apply(4, 4));
        assertEquals(5, f2.apply(3, 3, 4));
        assertEquals(10, f2.apply(3, 4, 3, 5, 2, 3));
    }

    @Test
    public void testWithTrailingArgs() {
        // arg 0 ... n-1 is summed up and diveded by the last arg
        VarArgFunction<Integer> f1 = values -> {
            return Arrays.stream(values, 0, values.length - 1).map(f -> (Integer) f).mapToInt(i -> i.intValue())
                    .sum() / (Integer) values[values.length - 1];
        };
        VarArgFunction<Integer> f2 = f1.withTrailingArgs(2);

        assertEquals(4, f2.apply(4, 4));
        assertEquals(5, f2.apply(3, 3, 4));
        assertEquals(10, f2.apply(3, 4, 3, 5, 2, 3));
    }

    @Test
    public void testVarArgFunctionConstant() {
        VarArgFunction<Integer> f1 = VarArgFunction.constant(42);

        assertEquals(42, f1.apply(1, 2));
        assertEquals(42, f1.apply(1, 2, 3));
        assertEquals(42, f1.apply(1, 2, 3, 4));
    }
}
