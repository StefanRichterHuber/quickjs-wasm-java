package io.github.stefanrichterhuber.quickjswasmjava;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueType;

public class JSJavaProxy {
    String type;
    Object value;

    public static JSJavaProxy from(MessageUnpacker unpacker) throws IOException {
        JSJavaProxy proxy = new JSJavaProxy();
        // First field is the type
        MessageFormat format = unpacker.getNextFormat();
        ValueType valueType = format.getValueType();

        if (valueType == ValueType.MAP) {
            final int mapSize = unpacker.unpackMapHeader();
            final String type = unpacker.unpackString();
            switch (type) {
                case "string":
                    proxy.value = unpacker.unpackString();
                    break;
                case "float":
                    proxy.value = unpacker.unpackDouble();
                    break;
                case "boolean":
                    proxy.value = unpacker.unpackBoolean();
                    break;
                case "int":
                    proxy.value = unpacker.unpackInt();
                    break;
                case "array":
                    int arraySize = unpacker.unpackArrayHeader();
                    List<Object> array = new ArrayList<>();
                    for (int i = 0; i < arraySize; i++) {
                        array.add(from(unpacker).value);
                    }
                    proxy.value = array;
                    break;
                case "object":
                    int objectSize = unpacker.unpackMapHeader();
                    Map<String, Object> object = new HashMap<>();
                    for (int i = 0; i < objectSize; i++) {
                        String key = unpacker.unpackString();
                        object.put(key, from(unpacker).value);
                    }
                    proxy.value = object;
                    break;
                default:
                    throw new RuntimeException("Unknown type: " + type);
            }
        } else if (valueType == ValueType.STRING) {
            String rawValue = unpacker.unpackString();
            if (rawValue.equals("null")) {
                proxy.value = null;
            } else if (rawValue.equals("undefined")) {
                proxy.value = null;
            }
        }

        return proxy;
    }
}
