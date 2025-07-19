package com.example.modernhabitrewire;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
public class UrlListEditorActivity extends AppCompatActivity {

    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;
    private UrlListRecyclerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_url_list_editor);


        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);

        EditText urlInput = findViewById(R.id.packageNameInput);
        Button addButton = findViewById(R.id.addButton);
        RecyclerView recyclerView = findViewById(R.id.urlRecyclerView);

        adapter = new UrlListRecyclerAdapter(appPreferencesManagerSingleton.getForbiddenUrls(), url -> {
            appPreferencesManagerSingleton.removeUrl(url);
            refreshList();
        });

        recyclerView.setAdapter(adapter);

        addButton.setOnClickListener(v -> {
            String newUrl = urlInput.getText().toString().trim();
            if (!newUrl.isEmpty()) {
                appPreferencesManagerSingleton.addForbiddenUrl(newUrl);
                urlInput.setText("");
                refreshList();
            }
        });
    }

    private void refreshList() {
        adapter.updateList(appPreferencesManagerSingleton.getForbiddenUrls());
    }


}