package tw.edu.socket.client;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import org.json.JSONObject;

public class ClientPaintActivity extends Activity implements ClientSession.Listener {
    public static final String EXTRA_CLIENT_ID = "client_id";
    public static final String EXTRA_CLIENT_NAME = "client_name";

    private ClientSession clientSession;
    private TextView connectionStatus;
    private TextView clientIdentity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_paint);
        connectionStatus = findViewById(R.id.paintClientStatus);
        clientIdentity = findViewById(R.id.paintClientIdentity);
        String name = getIntent().getStringExtra(EXTRA_CLIENT_NAME);
        clientIdentity.setText("使用者：" + (name == null ? "Client" : name));
        clientSession = ((ClientApplication) getApplication()).getClientSession();
    }

    @Override
    protected void onStart() {
        super.onStart();
        clientSession.attachListener(this, ClientSession.MODE_PAINT);
    }

    @Override
    protected void onStop() {
        clientSession.detachListener(this);
        super.onStop();
    }

    @Override
    public void onWelcome(String mode, String clientId, String name) {
        connectionStatus.setText("連線狀態：已連線至 Paint Mode");
    }

    @Override
    public void onEvent(JSONObject event) {
        // 第一階段不處理畫布資料或繪圖操作。
    }

    @Override
    public void onConnectionFailed(String reason) {
        connectionStatus.setText(reason);
        finish();
    }

    @Override
    public void onDisconnected(String reason) {
        connectionStatus.setText(reason);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (clientSession != null && (clientSession.isConnected() || clientSession.isConnecting())) {
            clientSession.leave();
        }
        super.onBackPressed();
    }
}
