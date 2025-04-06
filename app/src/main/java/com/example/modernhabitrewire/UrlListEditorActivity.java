package com.example.modernhabitrewire;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
public class UrlListEditorActivity extends AppCompatActivity {

    private AppPreferencesManager appPreferencesManager;
    private UrlListRecyclerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_url_list_editor);


        appPreferencesManager = new AppPreferencesManager(this);

        EditText urlInput = findViewById(R.id.urlInput);
        Button addButton = findViewById(R.id.addButton);
        RecyclerView recyclerView = findViewById(R.id.urlRecyclerView);

        adapter = new UrlListRecyclerAdapter(appPreferencesManager.getForbiddenUrls(), url -> {
            appPreferencesManager.removeUrl(url);
            refreshList();
        });

        recyclerView.setAdapter(adapter);

        addButton.setOnClickListener(v -> {
            String newUrl = urlInput.getText().toString().trim();
            if (!newUrl.isEmpty()) {
                appPreferencesManager.addUrl(newUrl);
                urlInput.setText("");
                refreshList();
            }
        });
    }

    private void refreshList() {
        adapter.updateList(appPreferencesManager.getForbiddenUrls());
    }


}