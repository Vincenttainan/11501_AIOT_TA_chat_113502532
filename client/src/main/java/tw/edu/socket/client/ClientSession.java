package tw.edu.socket.client;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-scoped connection owner so Activity navigation never opens another Socket. */
public final class ClientSession {
    public static final String MODE_TEXT = "text";
    public static final String MODE_PAINT = "paint";

    public interface Listener {
        void onWelcome(String mode, String clientId, String name, String color);
        void onEvent(JSONObject event);
        void onConnectionFailed(String reason);
        void onDisconnected(String reason);
    }

    private static final int MAX_BUFFERED_EVENTS = 5000;
    private final Object stateLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
    private final ArrayDeque<JSONObject> pendingEvents = new ArrayDeque<>();
    private volatile Listener listener;
    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;
    private volatile boolean connecting;
    private volatile boolean connected;
    private volatile boolean closing;
    private volatile String mode;
    private volatile String clientId;
    private volatile String clientName;
    private volatile String assignedColor;
    private volatile String lastDisconnectReason;
    private boolean awaitingPaintActivity;
    private boolean welcomeNotified;
    private long generation;

    public void attachListener(Listener listener, String screenMode) {
        List<JSONObject> buffered = new ArrayList<>();
        boolean replayWelcome;
        String currentMode;
        String currentId;
        String currentName;
        String currentColor;
        String disconnectedReason;
        synchronized (stateLock) {
            this.listener = listener;
            if (MODE_PAINT.equals(screenMode) && MODE_PAINT.equals(mode)) {
                awaitingPaintActivity = false;
            }
            if (listener != null && !awaitingPaintActivity) {
                while (!pendingEvents.isEmpty()) buffered.add(pendingEvents.removeFirst());
            }
            replayWelcome = connected && !welcomeNotified;
            currentMode = mode;
            currentId = clientId;
            currentName = clientName;
            currentColor = assignedColor;
            disconnectedReason = lastDisconnectReason;
        }

        if (listener == null) return;
        if (replayWelcome) deliverWelcome(listener, currentMode, currentId, currentName, currentColor);
        for (JSONObject event : buffered) deliverEvent(listener, event);
        if (!connected && !closing && disconnectedReason != null) listener.onDisconnected(disconnectedReason);
    }

    public void detachListener(Listener listener) {
        synchronized (stateLock) {
            if (this.listener == listener) this.listener = null;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isConnecting() {
        return connecting;
    }

    public String getMode() {
        return mode;
    }

    public void connect(String host, int port, String name) {
        final long attempt;
        synchronized (stateLock) {
            if (connecting || connected || closing) return;
            connecting = true;
            welcomeNotified = false;
            awaitingPaintActivity = false;
            pendingEvents.clear();
            lastDisconnectReason = null;
            mode = null;
            clientId = null;
            assignedColor = null;
            clientName = name;
            attempt = ++generation;
        }

        networkExecutor.execute(() -> {
            Socket newSocket = new Socket();
            try {
                newSocket.connect(new InetSocketAddress(host, port), 5000);
                BufferedReader newReader = new BufferedReader(new InputStreamReader(
                        newSocket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter newWriter = new BufferedWriter(new OutputStreamWriter(
                        newSocket.getOutputStream(), StandardCharsets.UTF_8));
                synchronized (stateLock) {
                    if (attempt != generation || closing) {
                        closeSocket(newSocket);
                        return;
                    }
                    socket = newSocket;
                    reader = newReader;
                    writer = newWriter;
                }
                JSONObject join = new JSONObject();
                join.put("type", "join");
                join.put("name", name);
                sendLine(newWriter, join.toString());
                networkExecutor.execute(() -> readLoop(attempt, newSocket, newReader));
            } catch (IOException | JSONException exception) {
                closeSocket(newSocket);
                handleConnectFailure(attempt, exception.getLocalizedMessage());
            }
        });
    }

    private void readLoop(long attempt, Socket activeSocket, BufferedReader activeReader) {
        try {
            String line;
            while (isCurrent(attempt, activeSocket) && (line = activeReader.readLine()) != null) {
                handleServerLine(attempt, line);
            }
            if (isCurrent(attempt, activeSocket)) {
                String reason = lastDisconnectReason;
                finishDisconnect(attempt, activeSocket,
                        reason == null ? "Server 已關閉連線" : "連線遭拒：" + reason);
            }
        } catch (IOException exception) {
            if (isCurrent(attempt, activeSocket)) {
                String reason = lastDisconnectReason;
                finishDisconnect(attempt, activeSocket,
                        reason == null ? "連線中斷" : "連線遭拒：" + reason);
            }
        }
    }

    private void handleServerLine(long attempt, String line) {
        try {
            JSONObject event = new JSONObject(line);
            if ("welcome".equals(event.optString("type"))) {
                String assignedMode = event.optString("mode", MODE_TEXT);
                if (!MODE_PAINT.equals(assignedMode)) assignedMode = MODE_TEXT;
                String assignedId = event.optString("clientId", "");
                String assignedName = event.optString("name", clientName);
                String color = event.optString("color", "#FF000000");
                synchronized (stateLock) {
                    if (attempt != generation) return;
                    mode = assignedMode;
                    clientId = assignedId;
                    clientName = assignedName;
                    assignedColor = color;
                    connecting = false;
                    connected = true;
                    awaitingPaintActivity = MODE_PAINT.equals(assignedMode);
                }
                mainHandler.post(() -> notifyWelcomeIfNeeded(attempt));
            } else {
                dispatchEvent(attempt, event);
                if ("error".equals(event.optString("type"))) {
                    lastDisconnectReason = event.optString("text", "連線遭拒");
                } else if ("kicked".equals(event.optString("type"))) {
                    lastDisconnectReason = event.optString("text", "你已被 Server 踢出聊天室");
                }
            }
        } catch (JSONException ignored) {
            JSONObject malformed = new JSONObject();
            try {
                malformed.put("type", "system");
                malformed.put("text", "收到格式錯誤的訊息，已忽略");
            } catch (JSONException impossible) {
                return;
            }
            dispatchEvent(attempt, malformed);
        }
    }

    private void notifyWelcomeIfNeeded(long attempt) {
        Listener current;
        String currentMode;
        String currentId;
        String currentName;
        String currentColor;
        synchronized (stateLock) {
            if (attempt != generation || !connected || welcomeNotified) return;
            current = listener;
            if (current == null) return;
            welcomeNotified = true;
            currentMode = mode;
            currentId = clientId;
            currentName = clientName;
            currentColor = assignedColor;
        }
        current.onWelcome(currentMode, currentId, currentName, currentColor);
    }

    private void deliverWelcome(Listener target, String currentMode, String currentId, String currentName, String currentColor) {
        synchronized (stateLock) {
            if (target != listener || !connected || welcomeNotified) return;
            welcomeNotified = true;
        }
        target.onWelcome(currentMode, currentId, currentName, currentColor);
    }

    private void dispatchEvent(long attempt, JSONObject event) {
        mainHandler.post(() -> {
            Listener target;
            synchronized (stateLock) {
                if (attempt != generation) return;
                target = listener;
                if (target == null || awaitingPaintActivity) {
                    if (pendingEvents.size() == MAX_BUFFERED_EVENTS) pendingEvents.removeFirst();
                    pendingEvents.addLast(event);
                    return;
                }
            }
            target.onEvent(event);
        });
    }

    private void deliverEvent(Listener target, JSONObject event) {
        if (target == listener) target.onEvent(event);
    }

    public void sendChatMessage(String text, Runnable onSent) {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "message");
            message.put("name", clientName);
            message.put("text", text);
            send(message.toString(), onSent);
        } catch (JSONException ignored) {
            // 固定欄位不會失敗。
        }
    }

    public void send(String line) {
        send(line, null);
    }

    private void send(String line, Runnable onSent) {
        BufferedWriter currentWriter = writer;
        long attempt;
        synchronized (stateLock) {
            attempt = generation;
            if (!connected || closing || currentWriter == null) return;
        }
        writerExecutor.execute(() -> {
            try {
                sendLine(currentWriter, line);
                if (onSent != null) {
                    mainHandler.post(() -> {
                        if (attempt == generation && connected) onSent.run();
                    });
                }
            } catch (IOException exception) {
                Socket activeSocket = socket;
                if (activeSocket != null) finishDisconnect(attempt, activeSocket, "訊息傳送失敗，連線已中斷");
            }
        });
    }

    public void leave() {
        Socket activeSocket;
        BufferedWriter activeWriter;
        long leaveAttempt;
        synchronized (stateLock) {
            activeSocket = socket;
            activeWriter = writer;
            if (!connected && !connecting) return;
            leaveAttempt = ++generation;
            closing = true;
            connected = false;
            connecting = false;
            welcomeNotified = false;
            awaitingPaintActivity = false;
            lastDisconnectReason = "已離開聊天室";
        }
        JSONObject leave = new JSONObject();
        try {
            leave.put("type", "leave");
            leave.put("name", clientName);
        } catch (JSONException ignored) {
            closeSocket(activeSocket);
            completeLeave(leaveAttempt, activeSocket, "已離開聊天室");
            return;
        }
        writerExecutor.execute(() -> {
            if (activeWriter != null) {
                try {
                    sendLine(activeWriter, leave.toString());
                } catch (IOException ignored) {
                    // Socket 仍會關閉並由 Server 偵測 EOF。
                }
            }
            closeSocket(activeSocket);
            completeLeave(leaveAttempt, activeSocket, "已離開聊天室");
        });
    }

    private void completeLeave(long attempt, Socket activeSocket, String reason) {
        synchronized (stateLock) {
            if (attempt != generation) return;
            socket = null;
            reader = null;
            writer = null;
            closing = false;
            lastDisconnectReason = reason;
        }
        notifyDisconnected(attempt, reason);
    }

    public void close() {
        Socket activeSocket;
        synchronized (stateLock) {
            closing = true;
            connected = false;
            connecting = false;
            generation++;
            activeSocket = socket;
            socket = null;
            reader = null;
            writer = null;
            listener = null;
        }
        closeSocket(activeSocket);
        networkExecutor.shutdownNow();
        writerExecutor.shutdownNow();
    }

    private void handleConnectFailure(long attempt, String reason) {
        Listener current;
        synchronized (stateLock) {
            if (attempt != generation) return;
            connecting = false;
            connected = false;
            closing = false;
            socket = null;
            reader = null;
            writer = null;
            current = listener;
            lastDisconnectReason = "連線失敗：" + (reason == null ? "無法連線至 Server" : reason);
        }
        String message = lastDisconnectReason;
        mainHandler.post(() -> {
            if (current != null && current == listener) current.onConnectionFailed(message);
            if (current != null && current == listener) current.onDisconnected(message);
        });
    }

    private void finishDisconnect(long attempt, Socket activeSocket, String reason) {
        synchronized (stateLock) {
            if (attempt != generation || socket != activeSocket) return;
            connected = false;
            connecting = false;
            closing = false;
            socket = null;
            reader = null;
            writer = null;
            lastDisconnectReason = reason;
        }
        closeSocket(activeSocket);
        notifyDisconnected(attempt, reason);
    }

    private void notifyDisconnected(long attempt, String reason) {
        mainHandler.post(() -> {
            Listener current;
            synchronized (stateLock) {
                if (attempt != generation) return;
                current = listener;
            }
            if (current != null) current.onDisconnected(reason);
        });
    }

    private boolean isCurrent(long attempt, Socket activeSocket) {
        synchronized (stateLock) {
            return attempt == generation && socket == activeSocket && !closing;
        }
    }

    private void sendLine(BufferedWriter output, String line) throws IOException {
        if (output == null) throw new IOException("目前沒有可用的連線");
        synchronized (output) {
            output.write(line);
            output.newLine();
            output.flush();
        }
    }

    private void closeSocket(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 已關閉。
        }
    }
}
