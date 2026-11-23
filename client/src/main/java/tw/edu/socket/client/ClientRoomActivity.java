package tw.edu.socket.client;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public class ClientRoomActivity extends Activity implements ClientSession.Listener {
    private ClientSession clientSession;
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
    private boolean paintActivityStarted;

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

        clientSession = ((ClientApplication) getApplication()).getClientSession();
        connectButton.setOnClickListener(view -> connectToServer());
        sendButton.setOnClickListener(view -> sendMessage());
        leaveButton.setOnClickListener(view -> leaveChat());
    }

    @Override
    protected void onStart() {
        super.onStart();
        clientSession.attachListener(this, "entry");
    }

    @Override
    protected void onStop() {
        clientSession.detachListener(this);
        super.onStop();
    }

    private void connectToServer() {
        if (clientSession.isConnected() || clientSession.isConnecting()) return;
        String clientName = clientNameInput.getText().toString().trim();
        String serverIp = serverIpInput.getText().toString().trim();
        String portText = serverPortInput.getText().toString().trim();
        if (clientName.isEmpty() || serverIp.isEmpty() || portText.isEmpty()) {
            connectionStatus.setText("連線失敗：請輸入 Client 名稱、Server IP 和 Port");
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new NumberFormatException("Port 超出範圍");
        } catch (NumberFormatException exception) {
            connectionStatus.setText("連線失敗：Port 必須介於 1 到 65535");
            return;
        }

        paintActivityStarted = false;
        connectButton.setEnabled(false);
        connectionStatus.setText("正在連線至 " + serverIp + ":" + port + "…");
        clientSession.connect(serverIp, port, clientName);
    }

    @Override
    public void onWelcome(String mode, String clientId, String name) {
        connectionStatus.setText("連線成功：" + name + "（" + mode + " Mode）");
        if (ClientSession.MODE_PAINT.equals(mode)) {
            if (paintActivityStarted || isFinishing()) return;
            paintActivityStarted = true;
            Intent intent = new Intent(this, ClientPaintActivity.class);
            intent.putExtra(ClientPaintActivity.EXTRA_CLIENT_ID, clientId);
            intent.putExtra(ClientPaintActivity.EXTRA_CLIENT_NAME, name);
            startActivity(intent);
            return;
        }
        connectButton.setEnabled(false);
        sendButton.setEnabled(true);
        leaveButton.setEnabled(true);
    }

    @Override
    public void onEvent(JSONObject event) {
        String type = event.optString("type");
        switch (type) {
            case "message":
                String sender = event.optString("name", "未知發送者");
                String text = event.optString("text", "");
                if (!text.isEmpty()) appendMessage(sender + "：" + text);
                break;
            case "system":
                String notice = event.optString("text", "");
                if (!notice.isEmpty()) appendMessage("【系統】" + notice);
                break;
            case "members":
                JSONArray names = event.optJSONArray("members");
                if (names != null) {
                    String[] memberNames = new String[names.length()];
                    for (int i = 0; i < names.length(); i++) memberNames[i] = names.optString(i);
                    clientMembers.setText("在線成員（" + names.length() + "）："
                            + TextUtils.join("、", memberNames));
                }
                break;
            case "error":
                String error = event.optString("text", "");
                if (!error.isEmpty()) appendMessage("【系統】" + error);
                break;
            default:
                break;
        }
    }

    @Override
    public void onConnectionFailed(String reason) {
        connectionStatus.setText(reason);
        connectButton.setEnabled(true);
        sendButton.setEnabled(false);
        leaveButton.setEnabled(false);
    }

    @Override
    public void onDisconnected(String reason) {
        connectionStatus.setText(reason);
        connectButton.setEnabled(true);
        sendButton.setEnabled(false);
        leaveButton.setEnabled(false);
        clientMembers.setText("在線成員：未連線");
        paintActivityStarted = false;
    }

    private void sendMessage() {
        String text = clientMessageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!clientSession.isConnected() || !ClientSession.MODE_TEXT.equals(clientSession.getMode())) {
            connectionStatus.setText("尚未連線至文字聊天室，無法傳送訊息");
            return;
        }
        sendButton.setEnabled(false);
        clientSession.sendChatMessage(text, () -> {
            if (!isFinishing() && clientSession.isConnected()) {
                clientMessageInput.setText("");
                sendButton.setEnabled(true);
            }
        });
    }

    private void leaveChat() {
        if (clientSession != null) clientSession.leave();
    }

    private void appendMessage(String message) {
        if (clientMessages == null || isFinishing()) return;
        String current = clientMessages.getText().toString();
        if (current.startsWith("目前沒有訊息")) current = "";
        clientMessages.setText(current.isEmpty() ? message : current + "\n" + message);
    }

    @Override
    public void onBackPressed() {
        if (clientSession != null && (clientSession.isConnected() || clientSession.isConnecting())) {
            clientSession.leave();
        }
        super.onBackPressed();
    }
}
