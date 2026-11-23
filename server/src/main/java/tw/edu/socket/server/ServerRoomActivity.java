package tw.edu.socket.server;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

public class ServerRoomActivity extends Activity implements ServerSession.Listener {
    public static final String EXTRA_SERVER_NAME = ServerSession.EXTRA_SERVER_NAME;
    private ServerSession serverSession;
    private TextView serverStatus;
    private TextView serverAddress;
    private TextView serverMessages;
    private TextView serverMembers;
    private EditText serverMessageInput;
    private String serverName;

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

        serverName = getIntent().getStringExtra(EXTRA_SERVER_NAME);
        TextView title = findViewById(R.id.serverRoomTitle);
        if (serverName != null && !serverName.trim().isEmpty()) {
            title.setText(serverName.trim() + "｜Server 管理畫面");
        }
        onMembers(Collections.singletonList("Server"));

        startServerSession();
    }

    private void startServerSession() {
        if (serverSession != null) return;
        serverSession = new ServerSession(serverName, ServerSession.MODE_TEXT, this);
        serverSession.start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startServerSession();
    }

    @Override
    protected void onStop() {
        stopServerSession();
        if (!isFinishing()) serverStatus.setText("狀態：App 已暫停；返回時會重新啟動");
        super.onStop();
    }

    private void stopServerSession() {
        if (serverSession == null) return;
        serverSession.setListener(null);
        serverSession.close();
        serverSession = null;
    }

    private void sendServerMessage() {
        String text = serverMessageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        serverMessageInput.setText("");
        serverSession.sendServerMessage(text);
    }

    @Override
    public void onStatus(String status, String ipAddress, int port) {
        serverStatus.setText("狀態：" + status);
        serverAddress.setText("本機 IP：" + ipAddress + "　Port：" + port);
    }

    @Override
    public void onLog(String message) {
        appendMessage(message);
    }

    @Override
    public void onMembers(List<String> names) {
        serverMembers.setText("在線成員（" + names.size() + "）：" + TextUtils.join("、", names));
    }

    @Override
    public void onChatMessage(String sender, String text) {
        appendMessage(sender + "：" + text);
    }

    private void appendMessage(String message) {
        if (serverMessages == null || isFinishing()) return;
        String current = serverMessages.getText().toString();
        if (current.startsWith("等待 Client 連線")) current = "";
        serverMessages.setText(current.isEmpty() ? message : current + "\n" + message);
    }

    @Override
    protected void onDestroy() {
        stopServerSession();
        super.onDestroy();
    }
}
