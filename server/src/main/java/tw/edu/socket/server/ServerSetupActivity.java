package tw.edu.socket.server;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

public class ServerSetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_setup);

        EditText serverNameInput = findViewById(R.id.serverNameInput);
        Button startButton = findViewById(R.id.startServerButton);
        startButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, ServerRoomActivity.class);
            intent.putExtra(ServerRoomActivity.EXTRA_SERVER_NAME, serverNameInput.getText().toString().trim());
            startActivity(intent);
        });
    }
}
