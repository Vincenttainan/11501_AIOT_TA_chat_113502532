package tw.edu.socket.server;

import android.os.Handler;
import android.os.Looper;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one server socket session. Activities only attach UI listeners. */
public final class ServerSession {
    public static final String EXTRA_SERVER_NAME = "server_name";
    public static final String EXTRA_SERVER_MODE = "server_mode";
    public static final String MODE_TEXT = "text";
    public static final String MODE_PAINT = "paint";
    public static final int PORT = 5000;

    public interface Listener {
        void onStatus(String status, String ipAddress, int port);
        void onLog(String message);
        void onMembers(List<String> names);
        void onChatMessage(String sender, String text);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object membersLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, ClientConnection> members = new LinkedHashMap<>();
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor();
    private final String serverName;
    private final String mode;
    private volatile Listener listener;
    private volatile ServerSocket serverSocket;
    private int connectionCount;

    public ServerSession(String serverName, String mode, Listener listener) {
        this.serverName = serverName == null || serverName.trim().isEmpty()
                ? "聊天室" : serverName.trim();
        this.mode = MODE_PAINT.equals(mode) ? MODE_PAINT : MODE_TEXT;
        this.listener = listener;
    }

    public String getMode() {
        return mode;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (closed.get()) return;
        post(listener -> listener.onStatus("啟動中…", "取得中…", PORT));
        acceptExecutor.execute(this::acceptClients);
    }

    private void acceptClients() {
        try {
            ServerSocket listeningSocket = new ServerSocket(PORT);
            serverSocket = listeningSocket;
            if (closed.get()) {
                closeServerSocket(listeningSocket);
                return;
            }
            String ipAddress = getLocalIpAddress();
            post(listener -> listener.onStatus("運作中，等待 Client 連線", ipAddress, PORT));

            while (!closed.get()) {
                Socket socket = listeningSocket.accept();
                try {
                    ClientConnection client = new ClientConnection(socket);
                    connections.add(client);
                    int number = ++connectionCount;
                    String address = socket.getInetAddress().getHostAddress();
                    post(listener -> listener.onLog("Client #" + number + " 連線成功：" + address));
                    clientExecutor.execute(() -> monitorClient(client));
                } catch (IOException exception) {
                    closeSocket(socket);
                    if (!closed.get()) post(listener -> listener.onLog("無法建立 Client 連線串流"));
                }
            }
        } catch (IOException exception) {
            if (!closed.get()) {
                String reason = exception.getLocalizedMessage();
                post(listener -> listener.onStatus("啟動失敗：" + reason, "尚未取得", PORT));
            }
        }
    }

    private void monitorClient(ClientConnection client) {
        try {
            String line;
            while (!closed.get() && (line = client.reader.readLine()) != null) {
                if (!handleClientMessage(client, line)) break;
            }
        } catch (IOException ignored) {
            // EOF 或 I/O 錯誤時由 finally 統一移除成員與 Socket。
        } finally {
            removeClient(client);
        }
    }

    private boolean handleClientMessage(ClientConnection client, String line) {
        try {
            JSONObject message = new JSONObject(line);
            String type = message.optString("type");
            if ("join".equals(type)) {
                return handleJoin(client, message.optString("name", "").trim());
            }
            if ("leave".equals(type)) return false;
            if ("message".equals(type)) handleChatMessage(client, message.optString("text", "").trim());
        } catch (JSONException ignored) {
            post(listener -> listener.onLog("收到格式錯誤的訊息，已忽略"));
        }
        return true;
    }

    private boolean handleJoin(ClientConnection client, String name) {
        String rejection = null;
        synchronized (membersLock) {
            if (client.memberName != null) {
                rejection = "此連線已加入聊天室";
            } else if (name.isEmpty()) {
                rejection = "名稱不可空白";
            } else if (isNameInUseLocked(name)) {
                rejection = "名稱已被使用，請更換名稱後重新連線";
            } else {
                client.memberName = name;
                client.clientId = UUID.randomUUID().toString();
                members.put(name, client);
                JSONObject welcome = new JSONObject();
                try {
                    welcome.put("type", "welcome");
                    welcome.put("mode", mode);
                    welcome.put("clientId", client.clientId);
                    welcome.put("name", name);
                    client.send(welcome.toString());
                } catch (JSONException | IOException exception) {
                    members.remove(name, client);
                    client.memberName = null;
                    client.close();
                    return false;
                }

                List<String> names = snapshotMembersLocked();
                Set<ClientConnection> recipients = new HashSet<>(members.values());
                enqueueSystemAndMembers(name + " 加入聊天室", names, recipients);
                post(listener -> {
                    listener.onLog(name + " 加入聊天室");
                    listener.onMembers(names);
                });
            }
        }
        if (rejection != null) {
            rejectClient(client, rejection);
            return false;
        }
        return true;
    }

    private boolean isNameInUseLocked(String name) {
        if ("Server".equalsIgnoreCase(name)) return true;
        for (String existing : members.keySet()) {
            if (existing.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private void handleChatMessage(ClientConnection client, String text) {
        if (text.isEmpty() || !MODE_TEXT.equals(mode)) return;
        String sender;
        synchronized (membersLock) {
            sender = client.memberName;
            if (sender == null || members.get(sender) != client) return;
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
        post(listener -> {
            listener.onChatMessage(sender, text);
        });
    }

    private void removeClient(ClientConnection client) {
        connections.remove(client);
        String name = null;
        synchronized (membersLock) {
            String assignedName = client.memberName;
            if (assignedName != null && members.remove(assignedName, client)) {
                name = assignedName;
                client.memberName = null;
                if (!closed.get()) {
                    List<String> names = snapshotMembersLocked();
                    enqueueSystemAndMembers(assignedName + " 已離開聊天室", names,
                            new HashSet<>(members.values()));
                    post(listener -> {
                        listener.onLog(assignedName + " 已離開聊天室");
                        listener.onMembers(names);
                    });
                }
            }
        }
        client.close();
        if (name == null && !closed.get()) {
            String address = client.socket.getInetAddress().getHostAddress();
            post(listener -> listener.onLog("未加入聊天室的 Client 已斷線：" + address));
        }
    }

    private void rejectClient(ClientConnection client, String reason) {
        JSONObject error = new JSONObject();
        try {
            error.put("type", "error");
            error.put("text", reason);
            client.send(error.toString());
        } catch (JSONException | IOException ignored) {
            // 連線已失效，直接關閉。
        }
        client.close();
    }

    public void sendServerMessage(String text) {
        String content = text == null ? "" : text.trim();
        if (content.isEmpty() || !MODE_TEXT.equals(mode) || closed.get()) return;
        JSONObject message = new JSONObject();
        try {
            message.put("type", "message");
            message.put("name", "Server");
            message.put("text", content);
        } catch (JSONException ignored) {
            return;
        }
        synchronized (membersLock) {
            enqueueBroadcast(message.toString(), new HashSet<>(members.values()));
        }
        post(listener -> {
            listener.onChatMessage("Server", content);
        });
    }

    private void enqueueSystemAndMembers(String text, List<String> names,
                                         Set<ClientConnection> recipients) {
        JSONObject system = new JSONObject();
        JSONObject membersMessage = new JSONObject();
        try {
            system.put("type", "system");
            system.put("text", text);
            membersMessage.put("type", "members");
            membersMessage.put("members", new JSONArray(names));
        } catch (JSONException ignored) {
            return;
        }
        enqueueBroadcastLines(java.util.Arrays.asList(system.toString(), membersMessage.toString()), recipients);
    }

    private void enqueueBroadcast(String line, Set<ClientConnection> recipients) {
        enqueueBroadcastLines(Collections.singletonList(line), recipients);
    }

    private void enqueueBroadcastLines(List<String> lines, Set<ClientConnection> recipients) {
        if (closed.get() || broadcastExecutor.isShutdown()) return;
        try {
            broadcastExecutor.execute(() -> {
                for (ClientConnection client : recipients) {
                    for (String line : lines) {
                        if (client.isClosed()) break;
                        try {
                            client.send(line);
                        } catch (IOException exception) {
                            client.close();
                            break;
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Session 結束時捨棄尚未送出的廣播。
        }
    }

    private List<String> snapshotMembersLocked() {
        List<String> result = new ArrayList<>();
        result.add("Server");
        result.addAll(members.keySet());
        return result;
    }

    private void post(ListenerAction action) {
        mainHandler.post(() -> {
            Listener current = listener;
            if (!closed.get() && current != null) action.run(current);
        });
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
            // 無法取得時仍可持續監聽。
        }
        return "尚未取得";
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeServerSocket(serverSocket);
        for (ClientConnection client : connections) client.close();
        acceptExecutor.shutdownNow();
        clientExecutor.shutdownNow();
        broadcastExecutor.shutdownNow();
        listener = null;
    }

    private void closeServerSocket(ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 已關閉。
        }
    }

    private void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 已關閉。
        }
    }

    private interface ListenerAction {
        void run(Listener listener);
    }

    private static final class ClientConnection {
        final Socket socket;
        final BufferedReader reader;
        final BufferedWriter writer;
        volatile String memberName;
        volatile String clientId;
        private volatile boolean closed;

        ClientConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized void send(String line) throws IOException {
            if (closed || socket.isClosed()) throw new IOException("連線已關閉");
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
                // 已關閉。
            }
        }
    }
}
