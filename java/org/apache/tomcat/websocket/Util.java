/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.websocket;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.Decoder;
import javax.websocket.Decoder.Binary;
import javax.websocket.Decoder.BinaryStream;
import javax.websocket.Decoder.Text;
import javax.websocket.Decoder.TextStream;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;

import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.websocket.pojo.PojoMessageHandlerWholeBinary;
import org.apache.tomcat.websocket.pojo.PojoMessageHandlerWholeText;

/**
 * Utility class for internal use only within the
 * {@link org.apache.tomcat.websocket} package.
 */
public class Util {

    private static final StringManager sm =
            StringManager.getManager(Constants.PACKAGE_NAME);
    private static final Queue<SecureRandom> randoms =
            new ConcurrentLinkedQueue<>();

    private Util() {
        // Hide default constructor
    }


    static boolean isControl(byte opCode) {
        return (opCode & 0x08) > 0;
    }


    static boolean isText(byte opCode) {
        return opCode == Constants.OPCODE_TEXT;
    }


    static CloseCode getCloseCode(int code) {
        if (code > 2999 && code < 5000) {
            return CloseCodes.NORMAL_CLOSURE;
        }
        switch (code) {
            case 1000:
                return CloseCodes.NORMAL_CLOSURE;
            case 1001:
                return CloseCodes.GOING_AWAY;
            case 1002:
                return CloseCodes.PROTOCOL_ERROR;
            case 1003:
                return CloseCodes.CANNOT_ACCEPT;
            case 1004:
                // Should not be used in a close frame
                // return CloseCodes.RESERVED;
                return CloseCodes.PROTOCOL_ERROR;
            case 1005:
                // Should not be used in a close frame
                // return CloseCodes.NO_STATUS_CODE;
                return CloseCodes.PROTOCOL_ERROR;
            case 1006:
                // Should not be used in a close frame
                // return CloseCodes.CLOSED_ABNORMALLY;
                return CloseCodes.PROTOCOL_ERROR;
            case 1007:
                return CloseCodes.NOT_CONSISTENT;
            case 1008:
                return CloseCodes.VIOLATED_POLICY;
            case 1009:
                return CloseCodes.TOO_BIG;
            case 1010:
                return CloseCodes.NO_EXTENSION;
            case 1011:
                return CloseCodes.UNEXPECTED_CONDITION;
            case 1012:
                // Not in RFC6455
                // return CloseCodes.SERVICE_RESTART;
                return CloseCodes.PROTOCOL_ERROR;
            case 1013:
                // Not in RFC6455
                // return CloseCodes.TRY_AGAIN_LATER;
                return CloseCodes.PROTOCOL_ERROR;
            case 1015:
                // Should not be used in a close frame
                // return CloseCodes.TLS_HANDSHAKE_FAILURE;
                return CloseCodes.PROTOCOL_ERROR;
            default:
                return CloseCodes.PROTOCOL_ERROR;
        }
    }


    static byte[] generateMask() {
        // SecureRandom is not thread-safe so need to make sure only one thread
        // uses it at a time. In theory, the pool could grow to the same size
        // as the number of request processing threads. In reality it will be
        // a lot smaller.

        // Get a SecureRandom from the pool
        SecureRandom sr = randoms.poll();

        // If one isn't available, generate a new one
        if (sr == null) {
            try {
                sr = SecureRandom.getInstance("SHA1PRNG");
            } catch (NoSuchAlgorithmException e) {
                // Fall back to platform default
                sr = new SecureRandom();
            }
        }

        // Generate the mask
        byte[] result = new byte[4];
        sr.nextBytes(result);

        // Put the SecureRandom back in the poll
        randoms.add(sr);

        return result;
    }


    static Class<?> getMessageType(MessageHandler listener) {
        return (Class<?>) Util.getGenericType(MessageHandler.class,
                listener.getClass());
    }


    public static Class<?> getDecoderType(Class<? extends Decoder> Decoder) {
        return (Class<?>) Util.getGenericType(Decoder.class, Decoder);
    }


    static Class<?> getEncoderType(Class<? extends Encoder> encoder) {
        return (Class<?>) Util.getGenericType(Encoder.class, encoder);
    }


    private static <T> Object getGenericType(Class<T> type,
            Class<? extends T> clazz) {

        // Look to see if this class implements the generic MessageHandler<>
        // interface

        // Get all the interfaces
        Type[] interfaces = clazz.getGenericInterfaces();
        for (Type iface : interfaces) {
            // Only need to check interfaces that use generics
            if (iface instanceof ParameterizedType) {
                ParameterizedType pi = (ParameterizedType) iface;
                // Look for the MessageHandler<> interface
                if (pi.getRawType() instanceof Class) {
                    if (type.isAssignableFrom((Class<?>) pi.getRawType())) {
                        return getTypeParameter(
                                clazz, pi.getActualTypeArguments()[0]);
                    }
                }
            }
        }

        // Interface not found on this class. Look at the superclass.
        Class<? extends T> superClazz =
                (Class<? extends T>) clazz.getSuperclass();

        Object result = getGenericType(type, superClazz);
        if (result instanceof Class<?>) {
            // Superclass implements interface and defines explicit type for
            // MessageHandler<>
            return result;
        } else if (result instanceof Integer) {
            // Superclass implements interface and defines unknown type for
            // MessageHandler<>
            // Map that unknown type to the generic types defined in this class
            ParameterizedType superClassType =
                    (ParameterizedType) clazz.getGenericSuperclass();
            return getTypeParameter(clazz,
                    superClassType.getActualTypeArguments()[
                            ((Integer) result).intValue()]);
        } else {
            // Error will be logged further up the call stack
            return null;
        }
    }


    /*
     * For a generic parameter, return either the Class used or if the type
     * is unknown, the index for the type in definition of the class
     */
    private static Object getTypeParameter(Class<?> clazz, Type argType) {
        if (argType instanceof Class<?>) {
            return argType;
        } else {
            TypeVariable<?>[] tvs = clazz.getTypeParameters();
            for (int i = 0; i < tvs.length; i++) {
                if (tvs[i].equals(argType)) {
                    return Integer.valueOf(i);
                }
            }
            return null;
        }
    }


    public static boolean isPrimitive(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            return true;
        } else if(clazz.equals(Boolean.class) ||
                clazz.equals(Byte.class) ||
                clazz.equals(Character.class) ||
                clazz.equals(Double.class) ||
                clazz.equals(Float.class) ||
                clazz.equals(Integer.class) ||
                clazz.equals(Long.class) ||
                clazz.equals(Short.class)) {
            return true;
        }
        return false;
    }


    public static Object coerceToType(Class<?> type, String value) {
        if (type.equals(String.class)) {
            return value;
        } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            return Boolean.valueOf(value);
        } else if (type.equals(byte.class) || type.equals(Byte.class)) {
            return Byte.valueOf(value);
        } else if (value.length() == 1 &&
                (type.equals(char.class) || type.equals(Character.class))) {
            return Character.valueOf(value.charAt(0));
        } else if (type.equals(double.class) || type.equals(Double.class)) {
            return Double.valueOf(value);
        } else if (type.equals(float.class) || type.equals(Float.class)) {
            return Float.valueOf(value);
        } else if (type.equals(int.class) || type.equals(Integer.class)) {
            return Integer.valueOf(value);
        } else if (type.equals(long.class) || type.equals(Long.class)) {
            return Long.valueOf(value);
        } else if (type.equals(short.class) || type.equals(Short.class)) {
            return Short.valueOf(value);
        } else {
            throw new IllegalArgumentException(sm.getString(
                    "util.invalidType", value, type.getName()));
        }
    }


    public static List<DecoderEntry> getDecoders(
            Class<? extends Decoder>[] decoderClazzes)
                    throws DeploymentException{

        List<DecoderEntry> result = new ArrayList<>();
        for (Class<? extends Decoder> decoderClazz : decoderClazzes) {
            // Need to instantiate decoder to ensure it is valid and that
            // deployment can be failed if it is not
            @SuppressWarnings("unused")
            Decoder instance;
            try {
                instance = decoderClazz.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                throw new DeploymentException(
                        sm.getString("pojoMethodMapping.invalidDecoder",
                                decoderClazz.getName()), e);
            }
            DecoderEntry entry = new DecoderEntry(
                    Util.getDecoderType(decoderClazz), decoderClazz);
            result.add(entry);
        }

        return result;
    }



    public static Set<MessageHandlerResult> getMessageHandlers(
            MessageHandler listener, EndpointConfig endpointConfig) {

        Class<?> target = Util.getMessageType(listener);

        // Will never be more than 2 types
        Set<MessageHandlerResult> results = new HashSet<>(2);

        // Simple cases - handlers already accepts one of the types expected by
        // the frame handling code
        if (String.class.isAssignableFrom(target)) {
            MessageHandlerResult result =
                    new MessageHandlerResult(listener,
                            MessageHandlerResultType.TEXT);
            results.add(result);
        } else if (ByteBuffer.class.isAssignableFrom(target)) {
            MessageHandlerResult result =
                    new MessageHandlerResult(listener,
                            MessageHandlerResultType.BINARY);
            results.add(result);
        } else if (PongMessage.class.isAssignableFrom(target)) {
            MessageHandlerResult result =
                    new MessageHandlerResult(listener,
                            MessageHandlerResultType.PONG);
            results.add(result);
        // Relatively simple cases - handler needs wrapping but no decoder to
        // convert it to one of the types expected by the frame handling code
        } else if (byte[].class.isAssignableFrom(target)) {
            MessageHandlerResult result = new MessageHandlerResult(
                    new PojoMessageHandlerWholeBinary(listener,
                            getOnMessageMethod(listener), null,
                            endpointConfig, null, new Object[1], 0, true, -1,
                            false),
                    MessageHandlerResultType.BINARY);
            results.add(result);
        } else if (InputStream.class.isAssignableFrom(target)) {
            MessageHandlerResult result = new MessageHandlerResult(
                    new PojoMessageHandlerWholeBinary(listener,
                            getOnMessageMethod(listener), null,
                            endpointConfig, null, new Object[1], 0, true, -1,
                            true),
                    MessageHandlerResultType.BINARY);
            results.add(result);
        } else if (Reader.class.isAssignableFrom(target)) {
            MessageHandlerResult result = new MessageHandlerResult(
                    new PojoMessageHandlerWholeText(listener,
                            getOnMessageMethod(listener), null,
                            endpointConfig, null, new Object[1], 0, true, -1),
                    MessageHandlerResultType.BINARY);
            results.add(result);
        } else {
        // More complex case - listener that requires a decoder
            DecoderMatch decoderMatch;
            try {
                decoderMatch = new DecoderMatch(target,
                        endpointConfig.getDecoders());
            } catch (DeploymentException e) {
                throw new IllegalArgumentException(e);
            }
            Method m = getOnMessageMethod(listener);
            if (decoderMatch.getBinaryDecoders().size() > 0) {
                MessageHandlerResult result = new MessageHandlerResult(
                        new PojoMessageHandlerWholeBinary(listener, m, null,
                                endpointConfig,
                                decoderMatch.getBinaryDecoders(), new Object[1],
                                0, false, -1, false),
                        MessageHandlerResultType.BINARY);
                results.add(result);
            }
            if (decoderMatch.getTextDecoders().size() > 0) {
                MessageHandlerResult result = new MessageHandlerResult(
                        new PojoMessageHandlerWholeText(listener, m, null,
                                endpointConfig,
                                decoderMatch.getTextDecoders(), new Object[1],
                                0, false, -1),
                        MessageHandlerResultType.TEXT);
                results.add(result);
            }
        }

        if (results.size() == 0) {
            throw new IllegalArgumentException(
                    sm.getString("wsSession.unknownHandler", listener, target));
        }

        return results;
    }


    private static Method getOnMessageMethod(MessageHandler listener) {
        try {
            return listener.getClass().getMethod("onMessage", Object.class);
        } catch (NoSuchMethodException | SecurityException e) {
            throw new IllegalArgumentException(
                    sm.getString("util.invalidMessageHandler"), e);
        }
    }

    public static class DecoderEntry {

        private final Class<?> clazz;
        private final Class<? extends Decoder> decoderClazz;

        public DecoderEntry(Class<?> clazz,
                Class<? extends Decoder> decoderClazz) {
            this.clazz = clazz;
            this.decoderClazz = decoderClazz;
        }

        public Class<?> getClazz() {
            return clazz;
        }

        public Class<? extends Decoder> getDecoderClazz() {
            return decoderClazz;
        }
    }


    private static class DecoderMatch {

        private final List<Class<? extends Decoder>> textDecoders =
                new ArrayList<>();
        private final List<Class<? extends Decoder>> binaryDecoders =
                new ArrayList<>();


        public DecoderMatch(Class<?> target, List<Class<? extends Decoder>> decoders)
                throws DeploymentException {
            List<DecoderEntry> decoderEntries = getDecoders(
                    decoders.toArray(new Class[decoders.size()]));
            for (DecoderEntry decoderEntry : decoderEntries) {
                if (decoderEntry.getClazz().isAssignableFrom(target)) {
                    if (Binary.class.isAssignableFrom(
                            decoderEntry.getDecoderClazz())) {
                        binaryDecoders.add(decoderEntry.getDecoderClazz());
                        // willDecode() method means this decoder may or may not
                        // decode a message so need to carry on checking for
                        // other matches
                    } else if (BinaryStream.class.isAssignableFrom(
                            decoderEntry.getDecoderClazz())) {
                        binaryDecoders.add(decoderEntry.getDecoderClazz());
                        // Stream decoders have to process the message so no
                        // more decoders can be matched
                        break;
                    } else if (Text.class.isAssignableFrom(
                            decoderEntry.getDecoderClazz())) {
                        textDecoders.add(decoderEntry.getDecoderClazz());
                        // willDecode() method means this decoder may or may not
                        // decode a message so need to carry on checking for
                        // other matches
                    } else if (TextStream.class.isAssignableFrom(
                            decoderEntry.getDecoderClazz())) {
                        textDecoders.add(decoderEntry.getDecoderClazz());
                        // Stream decoders have to process the message so no
                        // more decoders can be matched
                        break;
                    } else {
                        throw new IllegalArgumentException(
                                sm.getString("util.unknownDecoderType"));
                    }
                }
            }
        }


        public List<Class<? extends Decoder>> getTextDecoders() {
            return textDecoders;
        }


        public List<Class<? extends Decoder>> getBinaryDecoders() {
            return binaryDecoders;
        }
    }
}
