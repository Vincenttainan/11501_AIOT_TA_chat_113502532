package tw.edu.socket.client;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public class ClientHomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_home);
        Button enterButton = findViewById(R.id.enterChatButton);
        enterButton.setOnClickListener(view -> startActivity(new Intent(this, ClientRoomActivity.class)));
    }
}
