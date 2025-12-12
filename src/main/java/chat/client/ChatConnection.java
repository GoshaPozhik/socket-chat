package chat.client;

import chat.common.Protocol;

import javax.swing.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChatConnection implements Closeable {

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onServerMessage(Protocol.Decoded d);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger req = new AtomicInteger(1);
    private final Object outLock = new Object();

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private Thread readerThread;

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void connect(String host, int port, int timeoutMs) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);

        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new BufferedOutputStream(socket.getOutputStream());

        readerThread = new Thread(this::readLoop, "chat-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        fireConnected();
    }

    public int send(Protocol.Type type, Protocol.TLV... fields) {
        int requestId = req.getAndIncrement();
        byte[] frame = Protocol.encode(type, requestId, fields);
        try {
            synchronized (outLock) {
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            fireDisconnected("Не удалось отправить данные: " + e.getMessage());
        }
        return requestId;
    }

    public void sendHello(String username) {
        send(Protocol.Type.HELLO, Protocol.tlv(Protocol.F_USERNAME, username));
    }

    public void sendListRooms() {
        send(Protocol.Type.LIST);
    }

    public void sendCreateRoom(String room) {
        send(Protocol.Type.CREATE, Protocol.tlv(Protocol.F_ROOM, room));
    }

    public void sendJoinRoom(String room) {
        send(Protocol.Type.JOIN, Protocol.tlv(Protocol.F_ROOM, room));
    }

    public void sendLeaveRoom() {
        send(Protocol.Type.LEAVE);
    }

    public void sendChat(String text) {
        send(Protocol.Type.CHAT, Protocol.tlv(Protocol.F_TEXT, text));
    }

    private void readLoop() {
        try {
            while (true) {
                int len;
                try {
                    len = in.readInt();
                } catch (EOFException eof) {
                    fireDisconnected("Сервер закрыл соединение.");
                    return;
                }

                if (len <= 0 || len > Protocol.MAX_FRAME_BYTES) {
                    fireDisconnected("Получен повреждённый пакет от сервера.");
                    return;
                }

                byte[] body = new byte[len];
                in.readFully(body);

                Protocol.Decoded d;
                try {
                    d = Protocol.decodeBody(body);
                } catch (IOException e) {
                    fireDisconnected("Ошибка протокола: " + e.getMessage());
                    return;
                }

                fireServerMessage(d);
            }
        } catch (IOException e) {
            fireDisconnected("Соединение с сервером потеряно: " + e.getMessage());
        }
    }

    private void fireConnected() {
        SwingUtilities.invokeLater(() -> listeners.forEach(Listener::onConnected));
    }

    private void fireDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> listeners.forEach(l -> l.onDisconnected(reason)));
    }

    private void fireServerMessage(Protocol.Decoded d) {
        SwingUtilities.invokeLater(() -> listeners.forEach(l -> l.onServerMessage(d)));
    }

    @Override
    public void close() throws IOException {
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        if (socket != null && !socket.isClosed()) socket.close();
    }
}
