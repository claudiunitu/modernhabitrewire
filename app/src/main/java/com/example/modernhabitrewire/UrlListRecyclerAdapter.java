package com.example.modernhabitrewire;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

class UrlViewHolder extends RecyclerView.ViewHolder {
    TextView textView;
    ImageView deleteButton;

    UrlViewHolder(@NonNull View itemView, Boolean isLocked) {
        super(itemView);
        // Find the TextView and ImageView by their IDs
        textView = itemView.findViewById(R.id.url_text); // Correct ID of the TextView
        deleteButton = itemView.findViewById(R.id.delete_button); // Correct ID of the ImageView
        if(isLocked){
            deleteButton.setImageAlpha(40);
        }

    }
}
public class UrlListRecyclerAdapter extends RecyclerView.Adapter<UrlViewHolder> {

    Context context;
    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;

    public interface OnDeleteClickListener {
        void onDeleteClick(String url);
    }

    private List<String> urlList;
    private final OnDeleteClickListener deleteListener;

    public UrlListRecyclerAdapter(List<String> urlList, OnDeleteClickListener listener) {
        this.urlList = urlList;
        this.deleteListener = listener;
    }

    @NonNull
    @Override
    public UrlViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(context);
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.custom_url_editor_item, parent, false);
        return new UrlViewHolder(view, appPreferencesManagerSingleton.getIsBlockerActive());
    }


    @Override
    public void onBindViewHolder(@NonNull UrlViewHolder holder, int position) {

        String url = urlList.get(position);
        holder.textView.setText(url);
        holder.deleteButton.setOnClickListener(v -> {
            if(!appPreferencesManagerSingleton.getIsBlockerActive()){
                deleteListener.onDeleteClick(url);
            } else {
                Toast toast = Toast.makeText(context, "Blocker is active. Cannot remove item.",  Toast.LENGTH_SHORT);
                toast.show();
            }

        });
    }

    @Override
    public int getItemCount() {
        return urlList.size();
    }

    public void updateList(List<String> newList) {
        this.urlList = newList;
        notifyDataSetChanged();
    }


}