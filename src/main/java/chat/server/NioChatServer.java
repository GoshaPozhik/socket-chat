package chat.server;

import chat.common.FrameFramer;
import chat.common.Protocol;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NioChatServer {
    private static final int MAX_CLIENTS = 10;

    private final Map<String, ChatRoom> rooms = new HashMap<>();
    private final Map<SocketChannel, ClientSession> sessions = new HashMap<>();

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running;

    public static void main(String[] args) {
        new NioChatServer().runConsole();
    }

    private void runConsole() {
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);

        while (true) {
            System.out.print("Введите порт для запуска сервера: ");
            String raw = sc.nextLine().trim();
            int port;
            try {
                port = Integer.parseInt(raw);
                if (port < 1 || port > 65535) {
                    System.out.println("Порт должен быть в диапазоне 1-65535.");
                    continue;
                }
            } catch (NumberFormatException e) {
                System.out.println("Некорректный номер порта. Попробуйте ещё раз.");
                continue;
            }

            try {
                start(port);
                break;
            } catch (BindException be) {
                System.out.println("Порт " + port + " уже занят. Выберите другой порт.");
            } catch (IOException ioe) {
                System.out.println("Ошибка запуска сервера: " + ioe.getMessage());
            }
        }
    }

    private void start(int port) throws IOException {
        rooms.put("Lobby", new ChatRoom("Lobby"));

        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;
        System.out.println("Сервер запущен на порту " + port + ". Комната по умолчанию: Lobby");
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        while (running) {
            try {
                selector.select();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) onAccept(key);
                    if (key.isReadable()) onRead(key);
                    if (key.isWritable()) onWrite(key);
                }
            } catch (IOException e) {
                System.out.println("Ошибка селектора: " + e.getMessage());
            }
        }
    }

    private void stop() {
        running = false;
        System.out.println("Останавливаем сервер...");

        for (ClientSession s : new ArrayList<>(sessions.values())) {
            closeSession(s);
        }

        try { if (serverChannel != null) serverChannel.close(); } catch (IOException ignored) {}
        try { if (selector != null) selector.close(); } catch (IOException ignored) {}

        System.out.println("Сервер остановлен.");
    }

    private void onAccept(SelectionKey key) {
        try {
            SocketChannel ch = serverChannel.accept();
            if (ch == null) return;

            ch.configureBlocking(false);

            if (sessions.size() >= MAX_CLIENTS) {
                byte[] msg = Protocol.encode(Protocol.Type.ERROR, 0, Protocol.tlv(Protocol.F_TEXT,
                        "Сервер переполнен. Попробуйте позже."));
                ch.write(ByteBuffer.wrap(msg));
                ch.close();
                return;
            }

            SelectionKey clientKey = ch.register(selector, SelectionKey.OP_READ);
            ClientSession s = new ClientSession(ch, clientKey);
            clientKey.attach(s);
            sessions.put(ch, s);

        } catch (IOException e) {
            System.out.println("Ошибка принятия соединения: " + e.getMessage());
        }
    }

    private void onRead(SelectionKey key) {
        ClientSession s = (ClientSession) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();

        try {
            int read = ch.read(s.readBuffer);
            if (read == -1) {
                closeSession(s);
                return;
            }
            if (read == 0) return;

            s.readBuffer.flip();
            List<byte[]> frames = s.framer.feed(s.readBuffer);
            s.readBuffer.compact();

            for (byte[] body : frames) {
                if (body == null) {
                    // кадр слишком большой: пробуем отправить ошибку и закрываем соединение
                    byte[] err = Protocol.encode(Protocol.Type.ERROR, 0,
                            Protocol.tlv(Protocol.F_TEXT, "Слишком большой пакет."));
                    try {
                        ch.write(ByteBuffer.wrap(err));
                    } catch (IOException ignored) {
                    }
                    closeSession(s);
                    return;
                }

                Protocol.Decoded d;
                try {
                    d = Protocol.decodeBody(body);
                } catch (IOException ex) {
                    byte[] err = Protocol.encode(Protocol.Type.ERROR, 0,
                            Protocol.tlv(Protocol.F_TEXT, "Ошибка протокола."));
                    try {
                        ch.write(ByteBuffer.wrap(err));
                    } catch (IOException ignored) {
                    }
                    closeSession(s);
                    return;
                }

                handleMessage(s, d);
            }

        } catch (IOException e) {
            // сервер продолжает работать, закрываем только этого клиента
            closeSession(s);
        }
    }

    private void onWrite(SelectionKey key) {
        ClientSession s = (ClientSession) key.attachment();
        try {
            flushNow(s);
            if (s.outQueue.isEmpty()) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            closeSession(s);
        }
    }

    private void flushNow(ClientSession s) throws IOException {
        while (!s.outQueue.isEmpty()) {
            ByteBuffer buf = s.outQueue.peek();
            s.channel.write(buf);
            if (buf.hasRemaining()) break;
            s.outQueue.poll();
        }
    }

    private void interestWrite(SelectionKey key) {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    private void handleMessage(ClientSession s, Protocol.Decoded d) {
        switch (d.type()) {
            case HELLO -> onHello(s, d);
            case LIST -> onListRooms(s, d);
            case CREATE -> onCreateRoom(s, d);
            case JOIN -> onJoinRoom(s, d);
            case LEAVE -> onLeaveRoom(s, d);
            case CHAT -> onChat(s, d);
            default -> {}
        }
        if (!s.outQueue.isEmpty()) interestWrite(s.key);
    }

    private void onHello(ClientSession s, Protocol.Decoded d) {
        String name = Protocol.safeUsername(d.first(Protocol.F_USERNAME));
        if (name.isEmpty()) {
            s.queue(Protocol.encode(Protocol.Type.ERROR, d.requestId(),
                    Protocol.tlv(Protocol.F_TEXT, "Некорректное имя пользователя.")));
            return;
        }
        s.username = name;
        s.queue(Protocol.encode(Protocol.Type.OK, d.requestId()));
    }

    private void onListRooms(ClientSession s, Protocol.Decoded d) {
        if (!requireHello(s, d)) return;

        List<Protocol.TLV> tlvs = new ArrayList<>();
        for (String room : rooms.keySet().stream().sorted().toList()) {
            tlvs.add(Protocol.tlv(Protocol.F_ROOM, room));
        }
        s.queue(Protocol.encode(Protocol.Type.ROOMS, d.requestId(), tlvs.toArray(new Protocol.TLV[0])));
    }

    private void onCreateRoom(ClientSession s, Protocol.Decoded d) {
        if (!requireHello(s, d)) return;

        String roomName = Protocol.safeRoom(d.first(Protocol.F_ROOM));
        if (roomName.isEmpty()) {
            s.queue(Protocol.encode(Protocol.Type.ERROR, d.requestId(),
                    Protocol.tlv(Protocol.F_TEXT, "Некорректное имя комнаты.")));
            return;
        }
        if (rooms.containsKey(roomName)) {
            s.queue(Protocol.encode(Protocol.Type.ERROR, d.requestId(),
                    Protocol.tlv(Protocol.F_TEXT, "Комната уже существует.")));
            return;
        }

        rooms.put(roomName, new ChatRoom(roomName));
        s.queue(Protocol.encode(Protocol.Type.OK, d.requestId()));
    }

    private void onJoinRoom(ClientSession s, Protocol.Decoded d) {
        if (!requireHello(s, d)) return;

        String roomName = Protocol.safeRoom(d.first(Protocol.F_ROOM));
        ChatRoom room = rooms.get(roomName);
        if (room == null) {
            s.queue(Protocol.encode(Protocol.Type.ERROR, d.requestId(),
                    Protocol.tlv(Protocol.F_TEXT, "Комната не найдена.")));
            return;
        }

        leaveCurrentRoom(s);

        room.members.add(s);
        s.room = room;

        s.queue(Protocol.encode(Protocol.Type.JOINED, d.requestId(), Protocol.tlv(Protocol.F_ROOM, room.name)));
        broadcastSystem(room, "Пользователь " + s.username + " вошёл в комнату.");
    }

    private void onLeaveRoom(ClientSession s, Protocol.Decoded d) {
        if (!requireHello(s, d)) return;

        if (s.room == null) {
            s.queue(Protocol.encode(Protocol.Type.ERROR, d.requestId(),
                    Protocol.tlv(Protocol.F_TEXT, "Вы не находитесь в комнате.")));
            return;
        }

        ChatRoom old = s.room;
        leaveCurrentRoom(s);

        s.queue(Protocol.encode(Protocol.Type.LEFT, d.requestId()));
        broadcastSystem(old, "Пользователь " + s.username + " вышел из комнаты.");
    }

    private void onChat(ClientSession s, Protocol.Decoded d) {
        if (!requireHello(s, d)) return;

        if (s.room == null) {
            s.queue(Protocol.encode(Protocol.Type.ERROR, d.requestId(),
                    Protocol.tlv(Protocol.F_TEXT, "Сначала войдите в комнату.")));
            return;
        }

        String text = d.first(Protocol.F_TEXT);
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) return;

        if (Protocol.tooLongText(text)) {
            s.queue(Protocol.encode(Protocol.Type.ERROR, d.requestId(),
                    Protocol.tlv(Protocol.F_TEXT, "Сообщение слишком длинное. Максимум: " + Protocol.MAX_TEXT_CHARS)));
            return;
        }

        Protocol.TLV u = Protocol.tlv(Protocol.F_USERNAME, s.username);
        Protocol.TLV t = Protocol.tlv(Protocol.F_TEXT, text);
        Protocol.TLV r = Protocol.tlv(Protocol.F_ROOM, s.room.name);

        byte[] msg = Protocol.encode(Protocol.Type.MSG, 0, u, r, t);
        broadcastToRoom(s.room, msg);
    }

    private boolean requireHello(ClientSession s, Protocol.Decoded d) {
        if (s.username != null) return true;

        s.queue(Protocol.encode(Protocol.Type.ERROR, d.requestId(),
                Protocol.tlv(Protocol.F_TEXT, "Сначала отправьте HELLO.")));
        return false;
    }

    private void broadcastToRoom(ChatRoom room, byte[] frame) {
        for (ClientSession m : room.members) {
            m.queue(frame);
            interestWrite(m.key);
        }
    }

    private void broadcastSystem(ChatRoom room, String text) {
        byte[] frame = Protocol.encode(Protocol.Type.SYSTEM, 0,
                Protocol.tlv(Protocol.F_TEXT, text),
                Protocol.tlv(Protocol.F_ROOM, room.name));
        broadcastToRoom(room, frame);
    }

    private void leaveCurrentRoom(ClientSession s) {
        if (s.room == null) return;
        s.room.members.remove(s);
        s.room = null;
    }

    private void closeSession(ClientSession s) {
        ChatRoom room = s.room;
        String name = s.username;

        if (room != null && name != null) {
            // уведомим комнату о дисконнекте
            broadcastSystem(room, "Пользователь " + name + " отключился.");
        }

        leaveCurrentRoom(s);
        sessions.remove(s.channel);

        try { s.key.cancel(); } catch (Exception ignored) {}
        try { s.channel.close(); } catch (IOException ignored) {}
    }

    private static final class ChatRoom {
        final String name;
        final Set<ClientSession> members = new HashSet<>();
        ChatRoom(String name) { this.name = name; }
    }

    private static final class ClientSession {
        final SocketChannel channel;
        final SelectionKey key;

        final ByteBuffer readBuffer = ByteBuffer.allocate(16 * 1024);
        final FrameFramer framer = new FrameFramer(Protocol.MAX_FRAME_BYTES);
        final Queue<ByteBuffer> outQueue = new ArrayDeque<>();

        String username;
        ChatRoom room;

        ClientSession(SocketChannel channel, SelectionKey key) {
            this.channel = channel;
            this.key = key;
        }

        void queue(byte[] frame) {
            outQueue.add(ByteBuffer.wrap(frame));
        }
    }
}
