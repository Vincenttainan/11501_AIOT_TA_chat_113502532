package tw.edu.socket.server;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerRoomActivity extends Activity {
    public static final String EXTRA_SERVER_NAME = "server_name";
    private static final int SERVER_PORT = 5000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object membershipLock = new Object();
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    // 僅在 membershipLock 保護下存取，名稱與連線物件一對一綁定。
    private final Map<String, ClientConnection> members = new LinkedHashMap<>();
    private ExecutorService serverExecutor;
    private ExecutorService clientMonitorExecutor;
    private ExecutorService broadcastExecutor;
    private volatile ServerSocket serverSocket;
    private volatile boolean isClosing;
    private TextView serverStatus;
    private TextView serverAddress;
    private TextView serverMessages;
    private TextView serverMembers;
    private EditText serverMessageInput;
    private String serverName = "Server";
    private int connectionCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_room);

        serverStatus = findViewById(R.id.serverStatus);
        serverAddress = findViewById(R.id.serverAddress);
        serverMessages = findViewById(R.id.serverMessages);
        serverMembers = findViewById(R.id.serverMembers);
        serverMessageInput = findViewById(R.id.serverMessageInput);
        Button sendButton = findViewById(R.id.serverSendButton);
        sendButton.setOnClickListener(view -> sendServerMessage());

        String name = getIntent().getStringExtra(EXTRA_SERVER_NAME);
        if (name != null && !name.trim().isEmpty()) {
            serverName = name.trim();
            TextView title = findViewById(R.id.serverRoomTitle);
            title.setText(serverName + "｜Server 管理畫面");
        }
        updateServerMembers(Collections.singletonList("Server"));
        startServer();
    }

    private void startServer() {
        serverExecutor = Executors.newSingleThreadExecutor();
        clientMonitorExecutor = Executors.newCachedThreadPool();
        broadcastExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.execute(() -> {
            try {
                ServerSocket listeningSocket = new ServerSocket(SERVER_PORT);
                serverSocket = listeningSocket;
                if (isClosing) {
                    listeningSocket.close();
                    return;
                }
                String ipAddress = getLocalIpAddress();
                mainHandler.post(() -> {
                    if (!isClosing) {
                        serverStatus.setText("狀態：運作中，等待 Client 連線");
                        serverAddress.setText("本機 IP：" + ipAddress + "　Port：" + SERVER_PORT);
                    }
                });

                while (!isClosing) {
                    Socket socket = listeningSocket.accept();
                    try {
                        ClientConnection client = new ClientConnection(socket);
                        connections.add(client);
                        int clientNumber = ++connectionCount;
                        String peerAddress = socket.getInetAddress().getHostAddress();
                        mainHandler.post(() -> appendMessage("Client #" + clientNumber + " 連線成功：" + peerAddress));
                        clientMonitorExecutor.execute(() -> monitorClient(client));
                    } catch (IOException exception) {
                        closeSocket(socket);
                        if (!isClosing) {
                            mainHandler.post(() -> appendMessage("無法建立 Client 連線串流"));
                        }
                    }
                }
            } catch (IOException exception) {
                if (!isClosing) {
                    String reason = exception.getLocalizedMessage();
                    mainHandler.post(() -> {
                        serverStatus.setText("狀態：啟動失敗");
                        appendMessage("Server 啟動或監聽失敗：" + reason);
                    });
                }
            }
        });
    }

    private void monitorClient(ClientConnection client) {
        try {
            String line;
            while (!isClosing && (line = client.reader.readLine()) != null) {
                if (!handleClientMessage(client, line)) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // EOF 或 I/O 失敗都會在 finally 統一移除連線與成員。
        } finally {
            removeClient(client);
        }
    }

    /** @return true when the connection should continue to be read. */
    private boolean handleClientMessage(ClientConnection client, String line) {
        try {
            JSONObject incoming = new JSONObject(line);
            String type = incoming.optString("type");
            if ("join".equals(type)) {
                return handleJoin(client, incoming.optString("name", "").trim());
            }
            if ("leave".equals(type)) {
                return false;
            }
            if ("message".equals(type)) {
                handleChatMessage(client, incoming.optString("text", "").trim());
            }
        } catch (JSONException ignored) {
            mainHandler.post(() -> appendMessage("收到格式錯誤的訊息，已忽略"));
        }
        return true;
    }

    private boolean handleJoin(ClientConnection client, String name) {
        if (name.isEmpty()) {
            rejectClient(client, "名稱不可空白");
            return false;
        }

        List<String> memberNames;
        Set<ClientConnection> recipients;
        synchronized (membershipLock) {
            if (client.memberName != null) {
                rejectClient(client, "此連線已加入聊天室");
                return false;
            }
            boolean nameInUse = "Server".equalsIgnoreCase(name);
            for (String existingName : members.keySet()) {
                if (existingName.equalsIgnoreCase(name)) {
                    nameInUse = true;
                    break;
                }
            }
            if (nameInUse) {
                rejectClient(client, "名稱已被使用，請更換名稱後重新連線");
                return false;
            }
            client.memberName = name;
            members.put(name, client);
            memberNames = snapshotMemberNamesLocked();
            recipients = new HashSet<>(members.values());
            enqueueSystemAndMembers(name + " 加入聊天室", memberNames, recipients);
            mainHandler.post(() -> {
                appendMessage(name + " 加入聊天室");
                updateServerMembers(memberNames);
            });
        }
        return true;
    }

    private void handleChatMessage(ClientConnection client, String text) {
        if (text.isEmpty()) {
            return;
        }
        String sender;
        synchronized (membershipLock) {
            sender = client.memberName;
            if (sender == null || members.get(sender) != client) {
                return;
            }
            JSONObject message = new JSONObject();
            try {
                message.put("type", "message");
                message.put("name", sender);
                message.put("text", text);
            } catch (JSONException ignored) {
                return;
            }
            enqueueBroadcast(message.toString(), new HashSet<>(members.values()));
        }
        mainHandler.post(() -> appendMessage(sender + "：" + text));
    }

    private void removeClient(ClientConnection client) {
        connections.remove(client);
        String name = null;
        synchronized (membershipLock) {
            String assignedName = client.memberName;
            if (assignedName != null && members.remove(assignedName, client)) {
                name = assignedName;
                client.memberName = null;
                List<String> memberNames = snapshotMemberNamesLocked();
                Set<ClientConnection> recipients = new HashSet<>(members.values());
                if (!isClosing) {
                    enqueueSystemAndMembers(assignedName + " 已離開聊天室", memberNames, recipients);
                    mainHandler.post(() -> {
                        appendMessage(assignedName + " 已離開聊天室");
                        updateServerMembers(memberNames);
                    });
                }
            }
        }
        client.close();
        if (name == null && !isClosing) {
            String peerAddress = client.socket.getInetAddress().getHostAddress();
            mainHandler.post(() -> appendMessage("未加入聊天室的 Client 已斷線：" + peerAddress));
        }
    }

    private void rejectClient(ClientConnection client, String reason) {
        JSONObject error = new JSONObject();
        try {
            error.put("type", "error");
            error.put("text", reason);
            client.send(error.toString());
        } catch (JSONException | IOException ignored) {
            // 拒絕訊息無法送達時仍會關閉該連線。
        }
        client.close();
    }

    private void sendServerMessage() {
        String text = serverMessageInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        serverMessageInput.setText("");
        JSONObject message = new JSONObject();
        try {
            message.put("type", "message");
            message.put("name", "Server");
            message.put("text", text);
        } catch (JSONException ignored) {
            return;
        }
        appendMessage("Server：" + text);
        synchronized (membershipLock) {
            enqueueBroadcast(message.toString(), new HashSet<>(members.values()));
        }
    }

    private void enqueueSystemAndMembers(String text, List<String> memberNames,
                                         Set<ClientConnection> recipients) {
        JSONObject system = new JSONObject();
        JSONObject memberUpdate = new JSONObject();
        JSONArray names = new JSONArray(memberNames);
        try {
            system.put("type", "system");
            system.put("text", text);
            memberUpdate.put("type", "members");
            memberUpdate.put("members", names);
        } catch (JSONException ignored) {
            return;
        }
        enqueueBroadcastLines(java.util.Arrays.asList(system.toString(), memberUpdate.toString()), recipients);
    }

    private void enqueueBroadcast(String line, Set<ClientConnection> recipients) {
        enqueueBroadcastLines(Collections.singletonList(line), recipients);
    }

    private void enqueueBroadcastLines(List<String> lines, Set<ClientConnection> recipients) {
        if (broadcastExecutor == null || broadcastExecutor.isShutdown()) {
            return;
        }
        broadcastExecutor.execute(() -> {
            for (ClientConnection client : recipients) {
                if (!client.isClosed()) {
                    for (String line : lines) {
                        try {
                            client.send(line);
                        } catch (IOException exception) {
                            // 讀取執行緒會收到 Socket 關閉或 I/O 錯誤，並統一做離線清理。
                            client.close();
                            break;
                        }
                    }
                }
            }
        });
    }

    private List<String> snapshotMemberNamesLocked() {
        List<String> names = new ArrayList<>();
        names.add("Server");
        names.addAll(members.keySet());
        return names;
    }

    private void updateServerMembers(List<String> names) {
        if (serverMembers == null || isFinishing()) {
            return;
        }
        serverMembers.setText("在線成員（" + names.size() + "）：" + TextUtils.join("、", names));
    }

    private void appendMessage(String message) {
        if (serverMessages == null || isFinishing()) {
            return;
        }
        String current = serverMessages.getText().toString();
        if (current.startsWith("等待 Client 連線")) {
            current = "";
        }
        serverMessages.setText(current.isEmpty() ? message : current + "\n" + message);
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // 顯示尚未取得，仍可在監聽成功後繼續運作。
        }
        return "尚未取得";
    }

    private void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket 已關閉。
        }
    }

    @Override
    protected void onDestroy() {
        isClosing = true;
        ServerSocket listeningSocket = serverSocket;
        if (listeningSocket != null) {
            try {
                listeningSocket.close();
            } catch (IOException ignored) {
                // 關閉監聽 Socket 以解除 accept() 阻塞。
            }
        }
        for (ClientConnection client : connections) {
            client.close();
        }
        if (serverExecutor != null) serverExecutor.shutdownNow();
        if (clientMonitorExecutor != null) clientMonitorExecutor.shutdownNow();
        if (broadcastExecutor != null) broadcastExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static class ClientConnection {
        final Socket socket;
        final BufferedReader reader;
        final BufferedWriter writer;
        private volatile boolean closed;
        volatile String memberName;

        ClientConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized void send(String line) throws IOException {
            if (closed || socket.isClosed()) {
                throw new IOException("Client connection is closed");
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        }

        boolean isClosed() {
            return closed || socket.isClosed();
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket 已關閉。
            }
        }
    }
}
