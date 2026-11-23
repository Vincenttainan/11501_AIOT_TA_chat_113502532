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
        default void onStrokeAdded(PaintStroke stroke) { }
        default void onStrokesRemoved(List<String> strokeIds, long revision) { }
        default void onPaintMembers(List<PaintMember> members) { }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object membersLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, ClientConnection> members = new LinkedHashMap<>();
    private final List<PaintStroke> paintStrokes = new ArrayList<>();
    private final String[] palette = {"#E53935", "#1E88E5", "#43A047", "#8E24AA", "#FB8C00", "#00897B", "#3949AB", "#F4511E", "#6D4C41", "#039BE5", "#7CB342", "#D81B60"};
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService adminExecutor = Executors.newSingleThreadExecutor();
    private final String serverName;
    private final String mode;
    private volatile Listener listener;
    private volatile ServerSocket serverSocket;
    private int connectionCount;
    private long boardRevision;

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
            while (!closed.get() && (line = readBoundedLine(client.reader, 64 * 1024)) != null) {
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
            if ("stroke_submit".equals(type)) handleStrokeSubmit(client, message);
            if ("undo".equals(type)) handleUndo(client);
            if ("erase".equals(type)) handleErase(client, message.optString("strokeId", ""));
            if ("clear_mine".equals(type)) handleClearMine(client);
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
                if (MODE_PAINT.equals(mode)) client.color = chooseColorLocked();
                members.put(name, client);
                JSONObject welcome = new JSONObject();
                try {
                    welcome.put("type", "welcome");
                    welcome.put("mode", mode);
                    welcome.put("clientId", client.clientId);
                    welcome.put("name", name);
                    if (MODE_PAINT.equals(mode)) welcome.put("color", client.color);
                    client.send(welcome.toString());
                    if (MODE_PAINT.equals(mode)) sendBoardStateLocked(client);
                } catch (JSONException | IOException exception) {
                    members.remove(name, client);
                    client.memberName = null;
                    client.close();
                    return false;
                }

                List<String> names = snapshotMembersLocked();
                List<PaintMember> paintMembers = snapshotPaintMembersLocked();
                Set<ClientConnection> recipients = new HashSet<>(members.values());
                enqueueSystemAndMembers(name + " 加入聊天室", names, recipients);
                post(listener -> {
                    listener.onLog(name + " 加入聊天室");
                    listener.onMembers(names);
                    listener.onPaintMembers(paintMembers);
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

    private String chooseColorLocked() {
        Set<String> used = new HashSet<>();
        for (ClientConnection member : members.values()) if (member.color != null) used.add(member.color);
        for (String color : palette) if (!used.contains(color)) return color;
        // Keep colors distinct for larger rooms by cycling to a generated HSL hue.
        int hue = (members.size() * 137) % 360;
        return String.format(java.util.Locale.US, "#FF%02X%02X%02X",
                (int)(128 + 127 * Math.cos(Math.toRadians(hue))),
                (int)(128 + 127 * Math.cos(Math.toRadians(hue - 120))),
                (int)(128 + 127 * Math.cos(Math.toRadians(hue - 240))));
    }

    private void sendBoardStateLocked(ClientConnection client) throws JSONException, IOException {
        final int chunkSize=2;
        int chunks=Math.max(1,(paintStrokes.size()+chunkSize-1)/chunkSize);
        for(int chunk=0;chunk<chunks;chunk++) {
            JSONObject state = new JSONObject(); state.put("type", "board_state");
            state.put("revision", boardRevision); state.put("chunkIndex",chunk);
            state.put("complete",chunk==chunks-1);
            JSONArray strokes = new JSONArray();
            int start=chunk*chunkSize, end=Math.min(paintStrokes.size(),start+chunkSize);
            for(int i=start;i<end;i++) strokes.put(paintStrokes.get(i).toJson());
            state.put("strokes", strokes);
            client.send(state.toString());
        }
    }

    private void handleStrokeSubmit(ClientConnection client, JSONObject request) {
        if (!MODE_PAINT.equals(mode) || request.toString().length() > 64 * 1024) return;
        String requestId = request.optString("requestId", "");
        JSONArray rawPoints = request.optJSONArray("points");
        double rawWidth = request.optDouble("width", Double.NaN);
        if (requestId.isEmpty() || requestId.length() > 64 || rawPoints == null
                || rawPoints.length() < 1 || rawPoints.length() > PaintStroke.MAX_POINTS
                || !Double.isFinite(rawWidth) || rawWidth <= 0 || rawWidth > 0.2) return;
        List<PaintStroke.Point> points = new ArrayList<>();
        try {
            for (int i=0; i<rawPoints.length(); i++) {
                JSONObject point=rawPoints.getJSONObject(i);
                double x=point.getDouble("x"), y=point.getDouble("y");
                if (!Double.isFinite(x) || !Double.isFinite(y) || x<0 || x>1 || y<0 || y>1) return;
                points.add(new PaintStroke.Point((float)x,(float)y));
            }
        } catch (JSONException exception) { return; }
        PaintStroke accepted;
        synchronized (membersLock) {
            if (closed.get() || client.memberName == null || members.get(client.memberName) != client) return;
            accepted = new PaintStroke(UUID.randomUUID().toString(), requestId, client.clientId,
                    client.memberName, android.graphics.Color.parseColor(client.color), (float)rawWidth,
                    ++boardRevision, points);
            paintStrokes.add(accepted);
            JSONObject event = new JSONObject();
            try {
                event.put("type", "stroke_added"); event.put("revision", boardRevision);
                event.put("stroke", accepted.toJson());
            } catch (JSONException impossible) { return; }
            enqueueBroadcast(event.toString(), new HashSet<>(members.values()));
            PaintStroke stroke = accepted;
            post(listener -> listener.onStrokeAdded(stroke));
        }
    }

    private void handleUndo(ClientConnection client) {
        if (!MODE_PAINT.equals(mode)) return;
        synchronized (membersLock) {
            if (!isActiveMemberLocked(client)) return;
            for (int i = paintStrokes.size() - 1; i >= 0; i--) {
                PaintStroke stroke = paintStrokes.get(i);
                if (client.clientId.equals(stroke.clientId)) {
                    removeStrokeLocked(stroke);
                    return;
                }
            }
        }
    }

    private void handleErase(ClientConnection client, String strokeId) {
        if (!MODE_PAINT.equals(mode) || strokeId.isEmpty()) return;
        synchronized (membersLock) {
            if (!isActiveMemberLocked(client)) return;
            for (PaintStroke stroke : paintStrokes) {
                if (stroke.strokeId.equals(strokeId) && client.clientId.equals(stroke.clientId)) {
                    removeStrokeLocked(stroke);
                    return;
                }
            }
        }
    }

    private void handleClearMine(ClientConnection client) {
        if (!MODE_PAINT.equals(mode)) return;
        synchronized (membersLock) {
            if (!isActiveMemberLocked(client)) return;
            removeStrokesLocked(client.clientId, "mine", new HashSet<>(members.values()));
        }
    }

    private boolean isActiveMemberLocked(ClientConnection client) {
        return client.memberName != null && members.get(client.memberName) == client;
    }

    private void removeStrokeLocked(PaintStroke stroke) {
        if (!paintStrokes.remove(stroke)) return;
        long revision = ++boardRevision;
        JSONObject event = new JSONObject();
        try {
            event.put("type", "stroke_removed"); event.put("revision", revision);
            event.put("strokeId", stroke.strokeId); event.put("ownerId", stroke.clientId);
        } catch (JSONException ignored) { return; }
        enqueueBroadcast(event.toString(), new HashSet<>(members.values()));
        post(listener -> listener.onStrokesRemoved(Collections.singletonList(stroke.strokeId), revision));
    }

    private List<String> removeStrokesLocked(String ownerId, String scope,
                                              Set<ClientConnection> recipients) {
        List<String> removed = new ArrayList<>();
        for (int i = paintStrokes.size() - 1; i >= 0; i--) {
            PaintStroke stroke = paintStrokes.get(i);
            if (ownerId == null || ownerId.equals(stroke.clientId)) {
                removed.add(stroke.strokeId);
                paintStrokes.remove(i);
            }
        }
        if (removed.isEmpty()) return removed;
        long revision = ++boardRevision;
        broadcastRemovedStrokes(removed, ownerId, scope, revision, recipients);
        return removed;
    }

    private void broadcastRemovedStrokes(List<String> strokeIds, String ownerId, String scope,
                                         long revision, Set<ClientConnection> recipients) {
        final int chunkSize = 500;
        int chunks = Math.max(1, (strokeIds.size() + chunkSize - 1) / chunkSize);
        List<String> lines = new ArrayList<>();
        for (int chunk=0; chunk<chunks; chunk++) {
            JSONObject event = new JSONObject();
            try {
                event.put("type", "strokes_removed"); event.put("revision", revision);
                event.put("chunkIndex", chunk); event.put("complete", chunk == chunks - 1);
                int start=chunk*chunkSize, end=Math.min(strokeIds.size(),start+chunkSize);
                event.put("strokeIds", new JSONArray(strokeIds.subList(start,end)));
                event.put("scope", scope);
                if (ownerId != null) event.put("ownerId", ownerId);
                lines.add(event.toString());
            } catch (JSONException ignored) { return; }
        }
        enqueueBroadcastLines(lines, recipients);
        List<String> snapshot = new ArrayList<>(strokeIds);
        post(listener -> listener.onStrokesRemoved(snapshot, revision));
    }

    /** Server-only operation exposed to the Paint management screen. */
    public void clearAllPaintStrokes() {
        if (!MODE_PAINT.equals(mode) || closed.get()) return;
        executeAdmin(() -> {
            if (closed.get()) return;
            synchronized (membersLock) {
                removeStrokesLocked(null, "all", new HashSet<>(members.values()));
            }
        });
    }

    /** Server-only operation; the clientId comes from the server's member list. */
    public void clearUserPaintStrokes(String clientId) {
        if (!MODE_PAINT.equals(mode) || clientId == null || closed.get()) return;
        executeAdmin(() -> {
            if (closed.get()) return;
            synchronized (membersLock) {
                if (findMemberByIdLocked(clientId) == null) return;
                removeStrokesLocked(clientId, "user", new HashSet<>(members.values()));
            }
        });
    }

    /** Removes a member and their strokes, then sends a final kicked notice before closing the socket. */
    public void kickPaintClient(String clientId) {
        if (!MODE_PAINT.equals(mode) || clientId == null || closed.get()) return;
        executeAdmin(() -> kickPaintClientInternal(clientId));
    }

    private void kickPaintClientInternal(String clientId) {
        if (closed.get()) return;
        ClientConnection target;
        String kickedName;
        synchronized (membersLock) {
            target = findMemberByIdLocked(clientId);
            if (target == null) return;
            kickedName = target.memberName;
            members.remove(kickedName, target);
            target.memberName = null;
            target.intentionalRemoval = true;
            removeStrokesLocked(clientId, "kick", new HashSet<>(members.values()));
            List<String> names = snapshotMembersLocked();
            enqueueSystemAndMembers(kickedName + " 已被 Server 踢出聊天室", names,
                    new HashSet<>(members.values()));
            List<PaintMember> paintMembers = snapshotPaintMembersLocked();
            post(listener -> {
                listener.onLog(kickedName + " 已被 Server 踢出聊天室");
                listener.onMembers(names);
                listener.onPaintMembers(paintMembers);
            });
            if (clientExecutor.isShutdown()) {
                target.close(); connections.remove(target); return;
            }
            try {
                clientExecutor.execute(() -> {
                    JSONObject kicked = new JSONObject();
                    try {
                        kicked.put("type", "kicked");
                        kicked.put("text", "你已被 Server 踢出聊天室");
                        kicked.put("name", kickedName);
                    } catch (JSONException ignored) { }
                    try { target.send(kicked.toString()); } catch (IOException ignored) { }
                    target.close();
                    connections.remove(target);
                });
            } catch (RejectedExecutionException ignored) {
                target.close(); connections.remove(target);
            }
        }
    }

    private void executeAdmin(Runnable operation) {
        try { adminExecutor.execute(operation); }
        catch (RejectedExecutionException ignored) { }
    }

    private ClientConnection findMemberByIdLocked(String clientId) {
        for (ClientConnection member : members.values()) {
            if (clientId.equals(member.clientId)) return member;
        }
        return null;
    }

    private String readBoundedLine(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean overLimit = false;
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') {
                if (line.length() < maxChars) line.append((char)value);
                else overLimit = true;
            }
        }
        if (value == -1 && line.length() == 0 && !overLimit) return null;
        if (overLimit) throw new IOException("訊息超過大小限制");
        return line.toString();
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
                    removeStrokesLocked(client.clientId, "disconnect", new HashSet<>(members.values()));
                    List<String> names = snapshotMembersLocked();
                    List<PaintMember> paintMembers = snapshotPaintMembersLocked();
                    enqueueSystemAndMembers(assignedName + " 已離開聊天室", names,
                            new HashSet<>(members.values()));
                    post(listener -> {
                        listener.onLog(assignedName + " 已離開聊天室");
                        listener.onMembers(names);
                        listener.onPaintMembers(paintMembers);
                    });
                }
            }
        }
        client.close();
        if (name == null && !client.intentionalRemoval && !closed.get()) {
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

    public List<PaintStroke> getPaintStrokesSnapshot() {
        synchronized (membersLock) {
            return new ArrayList<>(paintStrokes);
        }
    }

    private List<PaintMember> snapshotPaintMembersLocked() {
        List<PaintMember> result = new ArrayList<>();
        for (ClientConnection client : members.values()) {
            result.add(new PaintMember(client.clientId, client.memberName,
                    client.socket.getInetAddress().getHostAddress(), client.color));
        }
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
        adminExecutor.shutdownNow();
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
        volatile String color;
        volatile boolean intentionalRemoval;
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
