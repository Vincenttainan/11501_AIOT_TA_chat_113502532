package tw.edu.socket.server;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;

public class ServerSetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_setup);

        EditText serverNameInput = findViewById(R.id.serverNameInput);
        Button startButton = findViewById(R.id.startServerButton);
        RadioGroup modeGroup = findViewById(R.id.serverModeGroup);
        startButton.setOnClickListener(view -> {
            String mode = modeGroup.getCheckedRadioButtonId() == R.id.paintModeOption
                    ? ServerSession.MODE_PAINT : ServerSession.MODE_TEXT;
            Class<?> targetActivity = ServerSession.MODE_PAINT.equals(mode)
                    ? ServerPaintActivity.class : ServerRoomActivity.class;
            Intent intent = new Intent(this, targetActivity);
            intent.putExtra(ServerSession.EXTRA_SERVER_NAME, serverNameInput.getText().toString().trim());
            intent.putExtra(ServerSession.EXTRA_SERVER_MODE, mode);
            startActivity(intent);
        });
    }
}
