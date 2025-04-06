package com.example.modernhabitrewire;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class UrlListRecyclerAdapter extends RecyclerView.Adapter<UrlListRecyclerAdapter.UrlViewHolder> {

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
//        View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.custom_url_editor_item, parent, false);
        return new UrlViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UrlViewHolder holder, int position) {

        String url = urlList.get(position);
        holder.textView.setText(url);
        holder.deleteButton.setOnClickListener(v -> {
            deleteListener.onDeleteClick(url);
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

    static class UrlViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        ImageView deleteButton;
        UrlViewHolder(@NonNull View itemView) {
            super(itemView);
            // Find the TextView and ImageView by their IDs
            textView = itemView.findViewById(R.id.url_text); // Correct ID of the TextView
            deleteButton = itemView.findViewById(R.id.delete_button); // Correct ID of the ImageView
        }
    }
}