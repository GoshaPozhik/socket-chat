package chat.client;

import chat.common.Protocol;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.List;

public class SwingChatClient implements ChatConnection.Listener {
    private final ChatConnection connection = new ChatConnection();

    private JFrame frame;
    private JPanel root;
    private CardLayout cards;

    private JLabel status;

    private ConnectPanel connectPanel;
    private RoomsPanel roomsPanel;
    private ChatPanel chatPanel;

    private String username;
    private String currentRoom;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SwingChatClient().initUI());
    }

    private void initUI() {
        connection.addListener(this);

        frame = new JFrame("NIO Chat (rooms)");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(820, 560);
        frame.setLocationRelativeTo(null);

        cards = new CardLayout();
        root = new JPanel(cards);

        connectPanel = new ConnectPanel();
        roomsPanel = new RoomsPanel();
        chatPanel = new ChatPanel();

        root.add(connectPanel, "connect");
        root.add(roomsPanel, "rooms");
        root.add(chatPanel, "chat");

        status = new JLabel("Отключено");
        status.setBorder(new EmptyBorder(6, 10, 6, 10));

        JPanel container = new JPanel(new BorderLayout());
        container.add(root, BorderLayout.CENTER);
        container.add(status, BorderLayout.SOUTH);

        frame.setContentPane(container);
        cards.show(root, "connect");
        frame.setVisible(true);
    }

    @Override
    public void onConnected() {
        setStatus("Подключено как " + username);
        roomsPanel.setControlsEnabled(true);

        // протокольная инициализация
        connection.sendHello(username);
        connection.sendListRooms();

        cards.show(root, "rooms");
    }

    @Override
    public void onDisconnected(String reason) {
        setStatus("Отключено");
        JOptionPane.showMessageDialog(frame, reason, "Ошибка", JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }

    @Override
    public void onServerMessage(Protocol.Decoded d) {
        switch (d.type()) {
            case OK -> { /* подтверждение без данных — можно игнорировать */ }
            case ERROR -> showError(d.first(Protocol.F_TEXT));
            case ROOMS -> roomsPanel.updateRooms(d.all(Protocol.F_ROOM));
            case JOINED -> {
                currentRoom = d.first(Protocol.F_ROOM);
                chatPanel.setRoom(currentRoom);
                chatPanel.setChatEnabled(true);
                cards.show(root, "chat");
            }
            case LEFT -> {
                currentRoom = null;
                chatPanel.setRoom(null);
                chatPanel.setChatEnabled(false);
                cards.show(root, "rooms");
                connection.sendListRooms();
            }
            case MSG -> {
                String from = d.first(Protocol.F_USERNAME);
                String text = d.first(Protocol.F_TEXT);
                if (from != null && text != null) chatPanel.appendUserMessage(from, text);
            }
            case SYSTEM -> {
                String text = d.first(Protocol.F_TEXT);
                if (text != null) chatPanel.appendSystemMessage(text);
            }
            default -> { /* неизвестные сообщения безопасно игнорируем */ }
        }
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void showError(String text) {
        if (text == null || text.isBlank()) text = "Неизвестная ошибка.";
        JOptionPane.showMessageDialog(frame, text, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    private final class ConnectPanel extends JPanel {
        private final JTextField host = new JTextField("127.0.0.1");
        private final JTextField port = new JTextField("7777");
        private final JTextField name = new JTextField();
        private final JButton connectBtn;

        ConnectPanel() {
            setLayout(new GridBagLayout());
            setBorder(new EmptyBorder(20, 20, 20, 20));

            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(6, 6, 6, 6);
            c.fill = GridBagConstraints.HORIZONTAL;

            JLabel title = new JLabel("Подключение");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
            c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
            add(title, c);

            c.gridwidth = 1;

            c.gridx = 0; c.gridy = 1;
            add(new JLabel("Host/IP:"), c);
            c.gridx = 1;
            add(host, c);

            c.gridx = 0; c.gridy = 2;
            add(new JLabel("Порт:"), c);
            c.gridx = 1;
            add(port, c);

            c.gridx = 0; c.gridy = 3;
            add(new JLabel("Имя:"), c);
            c.gridx = 1;
            add(name, c);

            connectBtn = new JButton("Подключиться");
            connectBtn.addActionListener(e -> doConnect());

            c.gridx = 0; c.gridy = 4; c.gridwidth = 2;
            add(connectBtn, c);
        }

        private void doConnect() {
            String h = host.getText().trim();
            String p = port.getText().trim();

            username = Protocol.safeUsername(name.getText());
            if (username.isEmpty()) {
                showError("Введите имя пользователя.");
                return;
            }

            int portNum;
            try {
                portNum = Integer.parseInt(p);
            } catch (NumberFormatException ex) {
                showError("Некорректный порт.");
                return;
            }

            setConnecting(true);
            setStatus("Подключение...");

            new Thread(() -> {
                try {
                    connection.connect(h, portNum, 2500);
                } catch (IOException ex) {
                    SwingUtilities.invokeLater(() -> {
                        setConnecting(false);
                        setStatus("Отключено");
                        showError("Не удалось подключиться: " + ex.getMessage());
                    });
                }
            }, "connect-thread").start();
        }

        private void setConnecting(boolean connecting) {
            host.setEnabled(!connecting);
            port.setEnabled(!connecting);
            name.setEnabled(!connecting);
            connectBtn.setEnabled(!connecting);
        }
    }

    private final class RoomsPanel extends JPanel {
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);

        private final JButton refreshBtn = new JButton("Обновить");
        private final JButton createBtn = new JButton("Создать");
        private final JButton joinBtn = new JButton("Войти");

        RoomsPanel() {
            setLayout(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(10, 10, 10, 10));

            JLabel title = new JLabel("Комнаты");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
            add(title, BorderLayout.NORTH);

            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) doJoin();
                }
            });

            add(new JScrollPane(list), BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
            buttons.add(refreshBtn);
            buttons.add(createBtn);
            buttons.add(joinBtn);
            add(buttons, BorderLayout.SOUTH);

            refreshBtn.addActionListener(e -> connection.sendListRooms());
            createBtn.addActionListener(e -> doCreate());
            joinBtn.addActionListener(e -> doJoin());

            setControlsEnabled(false);
        }

        void setControlsEnabled(boolean enabled) {
            list.setEnabled(enabled);
            refreshBtn.setEnabled(enabled);
            createBtn.setEnabled(enabled);
            joinBtn.setEnabled(enabled);
        }

        void updateRooms(List<String> rooms) {
            model.clear();
            for (String r : rooms) {
                if (r != null && !r.isBlank()) model.addElement(r);
            }
        }

        private void doCreate() {
            String room = JOptionPane.showInputDialog(frame, "Имя комнаты:", "Создать комнату",
                    JOptionPane.QUESTION_MESSAGE);
            if (room == null) return;
            room = Protocol.safeRoom(room);
            if (room.isEmpty()) {
                showError("Некорректное имя комнаты.");
                return;
            }
            connection.sendCreateRoom(room);
            connection.sendListRooms();
        }

        private void doJoin() {
            String room = list.getSelectedValue();
            if (room == null) {
                showError("Выберите комнату.");
                return;
            }
            connection.sendJoinRoom(room);
        }
    }

    private final class ChatPanel extends JPanel {
        private final JLabel roomLabel = new JLabel("Комната: -");
        private final JButton leaveBtn = new JButton("Выйти из комнаты");

        private final JTextPane pane = new JTextPane();
        private final JTextField input = new JTextField();
        private final JButton sendBtn = new JButton("Отправить");

        ChatPanel() {
            setLayout(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(10, 10, 10, 10));

            roomLabel.setFont(roomLabel.getFont().deriveFont(Font.BOLD, 16f));

            JPanel top = new JPanel(new BorderLayout());
            top.add(roomLabel, BorderLayout.WEST);
            top.add(leaveBtn, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);

            pane.setEditable(false);
            add(new JScrollPane(pane), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new BorderLayout(6, 6));
            bottom.add(input, BorderLayout.CENTER);
            bottom.add(sendBtn, BorderLayout.EAST);
            add(bottom, BorderLayout.SOUTH);

            sendBtn.addActionListener(this::onSend);
            input.addActionListener(this::onSend);

            leaveBtn.addActionListener(e -> {
                setChatEnabled(false); // пока ждём подтверждение LEFT
                connection.sendLeaveRoom();
            });

            setChatEnabled(false);
        }

        void setRoom(String room) {
            roomLabel.setText("Комната: " + (room == null ? "-" : room));
            clear();
        }

        void clear() {
            try {
                pane.getDocument().remove(0, pane.getDocument().getLength());
            } catch (BadLocationException ignored) {
            }
        }

        void setChatEnabled(boolean enabled) {
            input.setEnabled(enabled);
            sendBtn.setEnabled(enabled);
            leaveBtn.setEnabled(enabled);
        }

        void appendUserMessage(String from, String text) {
            StyledDocument doc = pane.getStyledDocument();
            try {
                Style nameStyle = doc.addStyle("name", null);
                StyleConstants.setBold(nameStyle, true);
                StyleConstants.setForeground(nameStyle, ColorUtil.colorForName(from));

                Style msgStyle = doc.addStyle("msg", null);
                StyleConstants.setForeground(msgStyle, Color.BLACK);

                doc.insertString(doc.getLength(), "[" + from + "] ", nameStyle);
                doc.insertString(doc.getLength(), text + "\n", msgStyle);
                pane.setCaretPosition(doc.getLength());
            } catch (BadLocationException ignored) {
            }
        }

        void appendSystemMessage(String text) {
            StyledDocument doc = pane.getStyledDocument();
            try {
                Style sysStyle = doc.addStyle("sys", null);
                StyleConstants.setForeground(sysStyle, Color.DARK_GRAY);
                StyleConstants.setItalic(sysStyle, true);

                doc.insertString(doc.getLength(), "[СИСТЕМА] " + text + "\n", sysStyle);
                pane.setCaretPosition(doc.getLength());
            } catch (BadLocationException ignored) {
            }
        }

        private void onSend(java.awt.event.ActionEvent e) {
            String msg = input.getText().trim();
            if (msg.isBlank()) return;
            input.setText("");
            connection.sendChat(msg);
        }
    }
}
