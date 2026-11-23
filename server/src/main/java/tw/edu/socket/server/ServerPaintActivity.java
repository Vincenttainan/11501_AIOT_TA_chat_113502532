package tw.edu.socket.server;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class ServerPaintActivity extends Activity implements ServerSession.Listener {
    private ServerSession serverSession;
    private String serverName;
    private TextView serverStatus;
    private TextView serverAddress;
    private TextView serverClientCount;
    private PaintCanvasView canvas;
    private LinearLayout membersContainer;

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
        canvas = findViewById(R.id.paintServerCanvas);
        membersContainer = findViewById(R.id.paintMembersContainer);
        Button clearAll = findViewById(R.id.paintClearAllButton);
        clearAll.setOnClickListener(view -> {
            if (serverSession != null) serverSession.clearAllPaintStrokes();
        });

        startServerSession();
    }

    private void startServerSession() {
        if (serverSession != null) {
            refreshAuthoritativeBoard();
            return;
        }
        serverSession = new ServerSession(serverName, ServerSession.MODE_PAINT, this);
        serverSession.start();
        refreshAuthoritativeBoard();
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
        serverClientCount.setText("在線使用者數：" + names.size());
    }

    @Override
    public void onPaintMembers(List<PaintMember> members) {
        membersContainer.removeAllViews();
        for (PaintMember member : members) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView identity = new TextView(this);
            identity.setText(member.name + "｜" + member.ipAddress + "｜" + member.color);
            identity.setTextColor(Color.BLACK);
            identity.setTextSize(12);
            identity.setPadding(6, 5, 6, 5);
            LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            TextView colorSwatch = new TextView(this);
            colorSwatch.setText("●");
            colorSwatch.setTextColor(Color.parseColor(member.color));
            colorSwatch.setTextSize(18);
            row.addView(colorSwatch, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(identity, identityParams);
            Button clearUser = new Button(this);
            clearUser.setText("Clear User");
            clearUser.setTextSize(10);
            clearUser.setPadding(4, 0, 4, 0);
            clearUser.setOnClickListener(view -> {
                if (serverSession != null) serverSession.clearUserPaintStrokes(member.clientId);
            });
            row.addView(clearUser, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            Button kick = new Button(this);
            kick.setText("Kick");
            kick.setTextSize(10);
            kick.setPadding(4, 0, 4, 0);
            kick.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("踢出使用者")
                    .setMessage("確定將 " + member.name + " 踢出聊天室並清除其筆跡嗎？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("踢出", (dialog, which) -> {
                        if (serverSession != null) serverSession.kickPaintClient(member.clientId);
                    }).show());
            row.addView(kick, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            membersContainer.addView(row);
        }
        serverClientCount.setText("在線使用者數：" + members.size());
    }

    @Override
    public void onChatMessage(String sender, String text) {
        // Paint Mode 不接受文字聊天訊息。
    }

    @Override
    public void onStrokeAdded(PaintStroke stroke) {
        refreshAuthoritativeBoard();
    }

    @Override
    public void onStrokesRemoved(List<String> strokeIds, long revision) {
        refreshAuthoritativeBoard();
    }

    private void refreshAuthoritativeBoard() {
        if (serverSession != null && canvas != null) {
            canvas.setBoard(serverSession.getPaintStrokesSnapshot());
        }
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
