package com.example.voward;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.List;
import java.util.ArrayList;

class AppPackagesViewHolder extends RecyclerView.ViewHolder {
    TextView textView;
    TextView appNameView;
    ImageView appIcon;
    ImageView deleteButton;
    MaterialCheckBox strictRuleCheckbox;

    AppPackagesViewHolder(@NonNull View itemView, Boolean isLocked) {
        super(itemView);
        // Find the TextView and ImageView by their IDs
        textView = itemView.findViewById(R.id.package_text);
        appNameView = itemView.findViewById(R.id.app_name_text);
        appIcon = itemView.findViewById(R.id.app_icon);
        deleteButton = itemView.findViewById(R.id.delete_button);
        strictRuleCheckbox = itemView.findViewById(R.id.strict_rule_checkbox);
        deleteButton.setVisibility(isLocked ? View.GONE : View.VISIBLE);
        strictRuleCheckbox.setClickable(!isLocked);
        strictRuleCheckbox.setFocusable(!isLocked);

    }
}
public class AppPackagesListRecyclerAdapter extends RecyclerView.Adapter<AppPackagesViewHolder> {

    Context context;
    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;

    public interface OnDeleteClickListener {
        void onDeleteClick(String packageName);
    }

    public interface OnStrictChangedListener {
        void onStrictChanged(String packageName, boolean strict);
    }

    private List<String> appPackagesList;
    private final OnDeleteClickListener deleteListener;
    private final OnStrictChangedListener strictChangedListener;

    public AppPackagesListRecyclerAdapter(
            List<String> appPackagesList,
            OnDeleteClickListener listener,
            OnStrictChangedListener strictChangedListener) {
        this.appPackagesList = appPackagesList;
        this.deleteListener = listener;
        this.strictChangedListener = strictChangedListener;
    }

    @NonNull
    @Override
    public AppPackagesViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(context);
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.custom_app_rule_item_modern, parent, false);
        return new AppPackagesViewHolder(view, appPreferencesManagerSingleton.getIsBlockerActive());
    }


    @Override
    public void onBindViewHolder(@NonNull AppPackagesViewHolder holder, int position) {

        String appPackage = appPackagesList.get(position);
        holder.textView.setText(appPackage);
        try {
            android.content.pm.ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(appPackage, 0);
            holder.appNameView.setText(context.getPackageManager().getApplicationLabel(info));
            holder.appIcon.setImageDrawable(context.getPackageManager().getApplicationIcon(info));
        } catch (Exception ignored) {
            holder.appNameView.setText(appPackage);
            holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        holder.strictRuleCheckbox.setOnCheckedChangeListener(null);
        holder.strictRuleCheckbox.setChecked(
                appPreferencesManagerSingleton.isStrictRestrictedApp(appPackage));
        holder.strictRuleCheckbox.setOnCheckedChangeListener((button, checked) -> {
            if (appPreferencesManagerSingleton.getIsBlockerActive()) {
                Toast.makeText(context, R.string.blocker_active_cannot_change,
                        Toast.LENGTH_SHORT).show();
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition != RecyclerView.NO_POSITION) notifyItemChanged(adapterPosition);
                return;
            }
            strictChangedListener.onStrictChanged(appPackage, checked);
        });
        holder.deleteButton.setOnClickListener(v -> {
            if(!appPreferencesManagerSingleton.getIsBlockerActive()){
                deleteListener.onDeleteClick(appPackage);
            } else {
                    Toast toast = Toast.makeText(context, context.getString(R.string.blocker_active_cannot_remove), Toast.LENGTH_SHORT);
                toast.show();
            }

        });
    }

    @Override
    public int getItemCount() {
        return appPackagesList.size();
    }

    public void updateList(List<String> newList) {
        int oldSize = appPackagesList.size();
        this.appPackagesList = new ArrayList<>(newList);
        // Strictness is separate state, so every retained row must be rebound even when
        // its package identity did not change (for example, regular -> strictly forbidden).
        int retained = Math.min(oldSize, appPackagesList.size());
        if (retained > 0) notifyItemRangeChanged(0, retained);
        if (appPackagesList.size() > oldSize) {
            notifyItemRangeInserted(oldSize, appPackagesList.size() - oldSize);
        } else if (oldSize > appPackagesList.size()) {
            notifyItemRangeRemoved(appPackagesList.size(), oldSize - appPackagesList.size());
        }
    }


}
