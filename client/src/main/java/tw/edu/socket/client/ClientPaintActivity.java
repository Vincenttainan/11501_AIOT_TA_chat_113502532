package tw.edu.socket.client;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class ClientPaintActivity extends Activity implements ClientSession.Listener {
    public static final String EXTRA_CLIENT_ID = "client_id";
    public static final String EXTRA_CLIENT_NAME = "client_name";
    public static final String EXTRA_CLIENT_COLOR = "client_color";

    private ClientSession clientSession;
    private TextView connectionStatus;
    private TextView clientIdentity;
    private PaintCanvasView canvas;
    private TextView membersView;
    private TextView noticesView;
    private Button eraserButton;
    private boolean eraserMode;
    private boolean boardReady;
    private long boardRevision;
    private final TreeMap<Long, PaintStroke> earlyStrokes = new TreeMap<>();
    private final List<PaintStroke> snapshotStrokes = new ArrayList<>();
    private long snapshotRevision;
    private long pendingRemovalRevision = -1;
    private final List<String> pendingRemovedStrokeIds = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_paint);
        connectionStatus = findViewById(R.id.paintClientStatus);
        clientIdentity = findViewById(R.id.paintClientIdentity);
        membersView = findViewById(R.id.paintClientMembers);
        noticesView = findViewById(R.id.paintClientNotices);
        canvas = findViewById(R.id.paintClientCanvas);
        canvas.setStrokeListener((requestId, points, width) -> submitStroke(requestId, points, width));
        canvas.setEraseListener(this::requestErase);
        findViewById(R.id.paintUndoButton).setOnClickListener(view -> sendSimpleCommand("undo"));
        eraserButton = findViewById(R.id.paintEraserButton);
        eraserButton.setOnClickListener(view -> setEraserMode(!eraserMode));
        findViewById(R.id.paintClearMineButton).setOnClickListener(view -> sendSimpleCommand("clear_mine"));
        String name = getIntent().getStringExtra(EXTRA_CLIENT_NAME);
        String clientId = getIntent().getStringExtra(EXTRA_CLIENT_ID);
        String color = getIntent().getStringExtra(EXTRA_CLIENT_COLOR);
        canvas.setOwnerId(clientId);
        clientIdentity.setText("使用者：" + (name == null ? "Client" : name));
        applyAssignedColor(name, color);
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
    public void onWelcome(String mode, String clientId, String name, String color) {
        connectionStatus.setText("連線狀態：已連線至 Paint Mode");
        canvas.setOwnerId(clientId);
        applyAssignedColor(name, color);
    }

    private void applyAssignedColor(String name, String color) {
        try {
            canvas.setUserColor(Color.parseColor(color));
            clientIdentity.setText("使用者：" + (name == null ? "Client" : name) + "｜顏色：" + color);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            canvas.setUserColor(Color.BLACK);
        }
    }

    @Override
    public void onEvent(JSONObject event) {
        String type=event.optString("type");
        if ("board_state".equals(type)) {
            JSONArray array=event.optJSONArray("strokes");
            if(array!=null) for(int i=0;i<array.length();i++) {
                try { snapshotStrokes.add(PaintStroke.fromJson(array.getJSONObject(i))); }
                catch (JSONException ignored) { }
            }
            snapshotRevision=event.optLong("revision",0);
            if(!event.optBoolean("complete",true))return;
            boardRevision=snapshotRevision; boardReady=true; canvas.setBoard(snapshotStrokes); canvas.setDrawingEnabled(true); snapshotStrokes.clear();
            while(!earlyStrokes.isEmpty()) {
                PaintStroke stroke=earlyStrokes.pollFirstEntry().getValue();
                if(stroke.revision>boardRevision) applyStroke(stroke);
            }
        } else if("stroke_added".equals(type)) {
            JSONObject raw=event.optJSONObject("stroke");
            if(raw==null)return;
            try {
                PaintStroke stroke=PaintStroke.fromJson(raw);
                if(!boardReady) { earlyStrokes.put(stroke.revision,stroke); return; }
                applyStroke(stroke);
            } catch (JSONException ignored) { }
        } else if ("stroke_removed".equals(type)) {
            if (event.optLong("revision", 0) <= boardRevision) return;
            boardRevision = event.optLong("revision", boardRevision);
            String id = event.optString("strokeId", "");
            if (!id.isEmpty()) canvas.removeStrokes(java.util.Collections.singletonList(id));
        } else if ("strokes_removed".equals(type)) {
            long revision=event.optLong("revision", 0);
            if (revision <= boardRevision) return;
            if (pendingRemovalRevision != revision) {
                pendingRemovalRevision=revision;
                pendingRemovedStrokeIds.clear();
            }
            JSONArray ids = event.optJSONArray("strokeIds");
            if (ids != null) {
                for (int i=0; i<ids.length(); i++) pendingRemovedStrokeIds.add(ids.optString(i));
            }
            if (event.optBoolean("complete", true)) {
                boardRevision=revision;
                canvas.removeStrokes(new ArrayList<>(pendingRemovedStrokeIds));
                pendingRemovedStrokeIds.clear();
                pendingRemovalRevision=-1;
            }
        } else if ("members".equals(type)) {
            JSONArray names = event.optJSONArray("members");
            if (names != null) {
                String[] values = new String[names.length()];
                for (int i=0; i<names.length(); i++) values[i] = names.optString(i);
                membersView.setText("在線成員：" + TextUtils.join("、", values));
            }
        } else if("system".equals(type)) {
            String text=event.optString("text","");
            if(!text.isEmpty()) appendNotice(text);
        } else if ("kicked".equals(type)) {
            String text=event.optString("text", "你已被 Server 踢出聊天室");
            appendNotice(text);
            connectionStatus.setText(text);
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            if (clientSession != null) clientSession.leave();
            finish();
        }
    }

    private void applyStroke(PaintStroke stroke) {
        if(stroke.revision<=boardRevision)return;
        boardRevision=stroke.revision;
        if(stroke.requestId!=null&&!stroke.requestId.isEmpty()) canvas.confirmStroke(stroke.requestId,stroke);
        else canvas.addRemoteStroke(stroke);
    }

    private void submitStroke(String requestId,List<PaintStroke.Point> points,float width) {
        if(clientSession==null||!clientSession.isConnected())return;
        JSONObject request=new JSONObject(); JSONArray array=new JSONArray();
        try {
            request.put("type","stroke_submit"); request.put("requestId",requestId); request.put("width",width);
            for(PaintStroke.Point point:points) { JSONObject p=new JSONObject(); p.put("x",point.x); p.put("y",point.y); array.put(p); }
            request.put("points",array); clientSession.send(request.toString());
        } catch(JSONException ignored) { }
    }

    private void sendSimpleCommand(String type) {
        if (clientSession == null || !clientSession.isConnected()) return;
        JSONObject command = new JSONObject();
        try { command.put("type", type); clientSession.send(command.toString()); }
        catch (JSONException ignored) { }
    }

    private void requestErase(String strokeId) {
        if (clientSession == null || !clientSession.isConnected()) return;
        JSONObject command = new JSONObject();
        try { command.put("type", "erase"); command.put("strokeId", strokeId); clientSession.send(command.toString()); }
        catch (JSONException ignored) { }
    }

    private void setEraserMode(boolean enabled) {
        eraserMode = enabled;
        canvas.setEraserMode(enabled);
        eraserButton.setText(enabled ? "橡皮擦：開" : "橡皮擦：關");
        connectionStatus.setText(enabled ? "橡皮擦模式：點選自己的筆跡以整筆刪除"
                : "連線狀態：已連線至 Paint Mode");
    }

    private void appendNotice(String text) {
        if (noticesView == null || text == null || text.isEmpty()) return;
        String old = noticesView.getText().toString();
        noticesView.setText(old.isEmpty() ? text : old + "\n" + text);
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
