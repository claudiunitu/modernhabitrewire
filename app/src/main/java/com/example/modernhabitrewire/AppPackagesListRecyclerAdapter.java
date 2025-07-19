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

class AppPackagesViewHolder extends RecyclerView.ViewHolder {
    TextView textView;
    ImageView deleteButton;

    AppPackagesViewHolder(@NonNull View itemView, Boolean isLocked) {
        super(itemView);
        // Find the TextView and ImageView by their IDs
        textView = itemView.findViewById(R.id.package_text); // Correct ID of the TextView
        deleteButton = itemView.findViewById(R.id.delete_button); // Correct ID of the ImageView
        if(isLocked){
            deleteButton.setImageAlpha(40);
        }

    }
}
public class AppPackagesListRecyclerAdapter extends RecyclerView.Adapter<AppPackagesViewHolder> {

    Context context;
    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;

    public interface OnDeleteClickListener {
        void onDeleteClick(String packageName);
    }

    private List<String> appPackagesList;
    private final OnDeleteClickListener deleteListener;

    public AppPackagesListRecyclerAdapter(List<String> appPackagesList, OnDeleteClickListener listener) {
        this.appPackagesList = appPackagesList;
        this.deleteListener = listener;
    }

    @NonNull
    @Override
    public AppPackagesViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(context);
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.custom_app_package_editor_item, parent, false);
        return new AppPackagesViewHolder(view, appPreferencesManagerSingleton.getIsBlockerActive());
    }


    @Override
    public void onBindViewHolder(@NonNull AppPackagesViewHolder holder, int position) {

        String appPackage = appPackagesList.get(position);
        holder.textView.setText(appPackage);
        holder.deleteButton.setOnClickListener(v -> {
            if(!appPreferencesManagerSingleton.getIsBlockerActive()){
                deleteListener.onDeleteClick(appPackage);
            } else {
                Toast toast = Toast.makeText(context, "Blocker is active. Cannot remove item.",  Toast.LENGTH_SHORT);
                toast.show();
            }

        });
    }

    @Override
    public int getItemCount() {
        return appPackagesList.size();
    }

    public void updateList(List<String> newList) {
        this.appPackagesList = newList;
        notifyDataSetChanged();
    }


}