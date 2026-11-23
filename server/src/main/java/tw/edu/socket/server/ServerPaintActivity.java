package tw.edu.socket.server;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;

import java.util.List;

public class ServerPaintActivity extends Activity implements ServerSession.Listener {
    private ServerSession serverSession;
    private String serverName;
    private TextView serverStatus;
    private TextView serverAddress;
    private TextView serverClientCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_paint);

        serverName = getIntent().getStringExtra(ServerSession.EXTRA_SERVER_NAME);
        TextView title = findViewById(R.id.paintServerTitle);
        if (serverName != null && !serverName.trim().isEmpty()) {
            title.setText(serverName.trim() + "｜Paint Server");
        }
        serverStatus = findViewById(R.id.paintServerStatus);
        serverAddress = findViewById(R.id.paintServerAddress);
        serverClientCount = findViewById(R.id.paintServerClientCount);

        startServerSession();
    }

    private void startServerSession() {
        if (serverSession != null) return;
        serverSession = new ServerSession(serverName, ServerSession.MODE_PAINT, this);
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
        if (!isFinishing()) serverStatus.setText("連線狀態：App 已暫停；返回時會重新啟動");
        super.onStop();
    }

    @Override
    public void onStatus(String status, String ipAddress, int port) {
        serverStatus.setText("連線狀態：" + status);
        serverAddress.setText("本機 IP：" + ipAddress + "　Port：" + port);
    }

    @Override
    public void onLog(String message) {
        // 第一階段只顯示模式、畫布與連線狀態。
    }

    @Override
    public void onMembers(List<String> names) {
        int clientCount = Math.max(0, names.size() - 1);
        serverClientCount.setText("已連線 Client：" + clientCount
                + (clientCount == 0 ? "" : "（" + TextUtils.join("、", names.subList(1, names.size())) + "）"));
    }

    @Override
    public void onChatMessage(String sender, String text) {
        // Paint Mode 不接受文字聊天訊息。
    }

    @Override
    protected void onDestroy() {
        stopServerSession();
        super.onDestroy();
    }

    private void stopServerSession() {
        if (serverSession == null) return;
        serverSession.setListener(null);
        serverSession.close();
        serverSession = null;
    }
}
