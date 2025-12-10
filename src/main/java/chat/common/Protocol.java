package chat.common;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Protocol {
    private Protocol() {}

    public static final byte VERSION = 1;

    // Ограничения протокола (защита от слишком больших пакетов)
    public static final int MAX_FRAME_BYTES = 8 * 1024;     // тело кадра
    public static final int MAX_TEXT_CHARS = 500;

    // TLV поля
    public static final byte F_USERNAME = 1;
    public static final byte F_ROOM     = 2;
    public static final byte F_TEXT     = 3;

    public enum Type {
        // client -> server
        HELLO(1),
        LIST(2),
        CREATE(3),
        JOIN(4),
        LEAVE(5),
        CHAT(6),

        // server -> client
        OK(100),
        ERROR(101),
        ROOMS(102),
        JOINED(103),
        LEFT(104),
        MSG(105),
        SYSTEM(106);

        public final int code;
        Type(int code) { this.code = code; }

        public static Type fromCode(int code) {
            for (Type t : values()) {
                if (t.code == code) return t;
            }
            return null;
        }
    }

    public record TLV(byte id, String value) {}

    public record Decoded(byte version, Type type, int requestId, Map<Byte, List<String>> fields) {
        public String first(byte fieldId) {
            List<String> v = fields.get(fieldId);
            return (v == null || v.isEmpty()) ? null : v.get(0);
        }
        public List<String> all(byte fieldId) {
            return fields.getOrDefault(fieldId, List.of());
        }
    }

    public static byte[] encode(Type type, int requestId, TLV... fields) {
        try {
            byte[] body = encodeBody(type, requestId, fields);
            ByteArrayOutputStream out = new ByteArrayOutputStream(4 + body.length);
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeInt(body.length);
            dos.write(body);
            dos.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Decoded decodeBody(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));

        byte version = in.readByte();
        if (version != VERSION) {
            // версию считаем ошибкой протокола
            return new Decoded(version, Type.ERROR, 0,
                    Map.of(F_TEXT, List.of("Неподдерживаемая версия протокола: " + version)));
        }

        int typeCode = Byte.toUnsignedInt(in.readByte());
        Type type = Type.fromCode(typeCode);
        int requestId = in.readInt();

        if (type == null) {
            return new Decoded(version, Type.ERROR, requestId,
                    Map.of(F_TEXT, List.of("Неизвестный тип сообщения: " + typeCode)));
        }

        Map<Byte, List<String>> fields = new HashMap<>();
        while (in.available() > 0) {
            byte id = in.readByte();
            int len = Short.toUnsignedInt(in.readShort());
            if (len > in.available()) {
                return new Decoded(version, Type.ERROR, requestId,
                        Map.of(F_TEXT, List.of("Повреждённый TLV-пакет.")));
            }
            byte[] data = in.readNBytes(len);
            String value = new String(data, StandardCharsets.UTF_8);
            fields.computeIfAbsent(id, k -> new ArrayList<>()).add(value);
        }

        return new Decoded(version, type, requestId, fields);
    }

    public static TLV tlv(byte id, String value) {
        return new TLV(id, value == null ? "" : value);
    }

    public static String safeRoom(String room) {
        if (room == null) return "";
        room = room.trim();
        if (room.isEmpty()) return "";
        // валидация имени комнаты
        if (room.length() > 30) room = room.substring(0, 30);
        return room.replaceAll("[\\r\\n\\t]", " ");
    }

    public static String safeUsername(String name) {
        if (name == null) return "";
        name = name.trim();
        if (name.isEmpty()) return "";
        if (name.length() > 20) name = name.substring(0, 20);
        return name.replaceAll("[\\r\\n\\t]", " ");
    }

    public static boolean tooLongText(String text) {
        return text != null && text.length() > MAX_TEXT_CHARS;
    }

    private static byte[] encodeBody(Type type, int requestId, TLV... fields) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        dos.writeByte(VERSION);
        dos.writeByte((byte) type.code);
        dos.writeInt(requestId);

        for (TLV f : fields) {
            byte[] data = f.value().getBytes(StandardCharsets.UTF_8);
            if (data.length > 65535) {
                data = Arrays.copyOf(data, 65535);
            }
            dos.writeByte(f.id());
            dos.writeShort((short) data.length);
            dos.write(data);
        }

        dos.flush();
        byte[] body = out.toByteArray();
        if (body.length > MAX_FRAME_BYTES) {
            //сообщения не должны разрастаться больше лимита
            throw new IOException("Frame too large: " + body.length);
        }
        return body;
    }
}
