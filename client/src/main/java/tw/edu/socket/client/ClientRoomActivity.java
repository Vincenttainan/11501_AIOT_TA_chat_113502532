package tw.edu.socket.client;

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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientRoomActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private volatile Socket clientSocket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;
    private volatile boolean isClosing;
    private volatile boolean isConnected;
    private volatile boolean isConnecting;
    private volatile String lastServerError;
    private String clientName;
    private EditText clientNameInput;
    private EditText serverIpInput;
    private EditText serverPortInput;
    private EditText clientMessageInput;
    private Button connectButton;
    private Button sendButton;
    private Button leaveButton;
    private TextView connectionStatus;
    private TextView clientMessages;
    private TextView clientMembers;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_room);

        clientNameInput = findViewById(R.id.clientNameInput);
        serverIpInput = findViewById(R.id.serverIpInput);
        serverPortInput = findViewById(R.id.serverPortInput);
        clientMessageInput = findViewById(R.id.clientMessageInput);
        connectButton = findViewById(R.id.connectButton);
        sendButton = findViewById(R.id.clientSendButton);
        leaveButton = findViewById(R.id.leaveChatButton);
        connectionStatus = findViewById(R.id.connectionStatus);
        clientMessages = findViewById(R.id.clientMessages);
        clientMembers = findViewById(R.id.clientMembers);
        sendButton.setEnabled(false);
        leaveButton.setEnabled(false);
        connectButton.setOnClickListener(view -> connectToServer());
        sendButton.setOnClickListener(view -> sendMessage());
        leaveButton.setOnClickListener(view -> leaveChat());
    }

    private void connectToServer() {
        if (isConnected || isConnecting) {
            return;
        }
        clientName = clientNameInput.getText().toString().trim();
        String serverIp = serverIpInput.getText().toString().trim();
        String portText = serverPortInput.getText().toString().trim();

        if (clientName.isEmpty() || serverIp.isEmpty() || portText.isEmpty()) {
            connectionStatus.setText("連線失敗：請輸入 Client 名稱、Server IP 和 Port");
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("Port 超出範圍");
            }
        } catch (NumberFormatException exception) {
            connectionStatus.setText("連線失敗：Port 必須介於 1 到 65535");
            return;
        }

        isClosing = false;
        isConnecting = true;
        lastServerError = null;
        connectButton.setEnabled(false);
        connectionStatus.setText("正在連線至 " + serverIp + ":" + port + "…");

        connectionExecutor.execute(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(serverIp, port), 5000);
                if (isClosing) {
                    closeSocket(socket);
                    return;
                }
                BufferedReader newReader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter newWriter = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                clientSocket = socket;
                reader = newReader;
                writer = newWriter;
                sendLine(newWriter, createJoinMessage(clientName));
                isConnecting = false;
                isConnected = true;
                mainHandler.post(() -> {
                    if (!isClosing && clientSocket == socket) {
                        connectionStatus.setText("連線成功：" + clientName + " 已連線至 " + serverIp + ":" + port);
                        connectButton.setEnabled(false);
                        sendButton.setEnabled(true);
                        leaveButton.setEnabled(true);
                    }
                });
                readExecutor.execute(() -> readMessages(socket, newReader));
            } catch (IOException exception) {
                closeSocket(socket);
                if (clientSocket == socket) {
                    clientSocket = null;
                    reader = null;
                    writer = null;
                }
                isConnecting = false;
                String reason = exception.getLocalizedMessage();
                mainHandler.post(() -> {
                    if (!isClosing) {
                        connectionStatus.setText("連線失敗：" + (reason == null ? "無法連線至 Server" : reason));
                        connectButton.setEnabled(true);
                    }
                });
            }
        });
    }

    private void readMessages(Socket socket, BufferedReader socketReader) {
        try {
            String line;
            while (!isClosing && clientSocket == socket && (line = socketReader.readLine()) != null) {
                displayMessage(line);
            }
            if (!isClosing && clientSocket == socket) {
                String error = lastServerError;
                mainHandler.post(() -> finishRemoteDisconnect(socket,
                        error == null ? "Server 已關閉連線" : "連線遭拒：" + error));
            }
        } catch (IOException exception) {
            if (!isClosing && clientSocket == socket) {
                mainHandler.post(() -> finishRemoteDisconnect(socket, "連線中斷"));
            }
        }
    }

    private void displayMessage(String line) {
        try {
            JSONObject message = new JSONObject(line);
            String type = message.optString("type");
            String text = message.optString("text", "");
            switch (type) {
                case "message":
                    String sender = message.optString("name", "未知發送者");
                    if (!text.isEmpty()) {
                        mainHandler.post(() -> appendMessage(sender + "：" + text));
                    }
                    break;
                case "system":
                    if (!text.isEmpty()) {
                        mainHandler.post(() -> appendMessage("【系統】" + text));
                    }
                    break;
                case "members":
                    JSONArray names = message.optJSONArray("members");
                    if (names != null) {
                        String[] memberNames = new String[names.length()];
                        for (int i = 0; i < names.length(); i++) {
                            memberNames[i] = names.optString(i);
                        }
                        mainHandler.post(() -> clientMembers.setText("在線成員（"
                                + memberNames.length + "）：" + TextUtils.join("、", memberNames)));
                    }
                    break;
                case "error":
                    lastServerError = text;
                    if (!text.isEmpty()) {
                        mainHandler.post(() -> appendMessage("【系統】" + text));
                    }
                    break;
                default:
                    break;
            }
        } catch (JSONException ignored) {
            mainHandler.post(() -> appendMessage("收到格式錯誤的訊息，已忽略"));
        }
    }

    private void sendMessage() {
        String text = clientMessageInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!isConnected) {
            connectionStatus.setText("尚未連線，無法傳送訊息");
            return;
        }
        String line = createChatMessage(clientName, text);
        sendButton.setEnabled(false);
        writeExecutor.execute(() -> {
            try {
                sendLine(writer, line);
                mainHandler.post(() -> {
                    if (isConnected) {
                        clientMessageInput.setText("");
                        sendButton.setEnabled(true);
                    }
                });
            } catch (IOException exception) {
                mainHandler.post(() -> disconnect("訊息傳送失敗，連線已中斷"));
            }
        });
    }

    private void leaveChat() {
        Socket socket = clientSocket;
        BufferedWriter socketWriter = writer;
        if (socket == null || socketWriter == null) {
            disconnect("已離開聊天室");
            return;
        }

        isClosing = true;
        isConnected = false;
        connectButton.setEnabled(false);
        sendButton.setEnabled(false);
        leaveButton.setEnabled(false);
        connectionStatus.setText("正在離開聊天室…");

        JSONObject leave = new JSONObject();
        try {
            leave.put("type", "leave");
            leave.put("name", clientName);
        } catch (JSONException ignored) {
            closeSocket(socket);
            if (clientSocket == socket) {
                clientSocket = null;
                reader = null;
                writer = null;
            }
            connectionStatus.setText("已離開聊天室");
            connectButton.setEnabled(true);
            clientMembers.setText("在線成員：未連線");
            return;
        }
        writeExecutor.execute(() -> {
            try {
                sendLine(socketWriter, leave.toString());
            } catch (IOException ignored) {
                // 即使送出 leave 失敗，也會關閉本機 Socket 並由 Server 偵測 EOF。
            } finally {
                closeSocket(socket);
                mainHandler.post(() -> {
                    if (clientSocket == socket) {
                        clientSocket = null;
                        reader = null;
                        writer = null;
                        if (!isFinishing()) {
                            connectionStatus.setText("已離開聊天室");
                            connectButton.setEnabled(true);
                            clientMembers.setText("在線成員：未連線");
                        }
                    }
                });
            }
        });
    }

    private String createJoinMessage(String name) throws IOException {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "join");
            message.put("name", name);
            return message.toString();
        } catch (JSONException exception) {
            throw new IOException("無法建立加入訊息", exception);
        }
    }

    private String createChatMessage(String name, String text) {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "message");
            message.put("name", name);
            message.put("text", text);
            return message.toString();
        } catch (JSONException exception) {
            return "";
        }
    }

    private void sendLine(BufferedWriter output, String line) throws IOException {
        if (output == null) {
            throw new IOException("目前沒有可用的連線");
        }
        synchronized (output) {
            output.write(line);
            output.newLine();
            output.flush();
        }
    }

    private void appendMessage(String message) {
        if (clientMessages == null || isFinishing()) {
            return;
        }
        String current = clientMessages.getText().toString();
        if (current.startsWith("目前沒有訊息")) {
            current = "";
        }
        clientMessages.setText(current.isEmpty() ? message : current + "\n" + message);
    }

    private void disconnect(String status) {
        isClosing = true;
        isConnected = false;
        isConnecting = false;
        closeClientSocket();
        if (!isFinishing()) {
            connectionStatus.setText(status);
            connectButton.setEnabled(true);
            sendButton.setEnabled(false);
            leaveButton.setEnabled(false);
            clientMembers.setText("在線成員：未連線");
        }
    }

    private void finishRemoteDisconnect(Socket socket, String status) {
        if (clientSocket != socket) {
            return;
        }
        disconnect(status);
    }

    private void closeClientSocket() {
        Socket socket = clientSocket;
        clientSocket = null;
        reader = null;
        writer = null;
        closeSocket(socket);
    }

    private void closeSocket(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket 可能已由遠端關閉。
            }
        }
    }

    @Override
    protected void onDestroy() {
        isClosing = true;
        isConnected = false;
        isConnecting = false;
        closeClientSocket();
        connectionExecutor.shutdownNow();
        readExecutor.shutdownNow();
        writeExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
