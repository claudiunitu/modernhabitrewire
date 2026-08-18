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

class UrlViewHolder extends RecyclerView.ViewHolder {
    TextView textView;
    TextView typeView;
    ImageView deleteButton;
    MaterialCheckBox strictRuleCheckbox;
    TextView strictBadge;

    UrlViewHolder(@NonNull View itemView, Boolean isLocked) {
        super(itemView);
        // Find the TextView and ImageView by their IDs
        textView = itemView.findViewById(R.id.url_text);
        typeView = itemView.findViewById(R.id.url_rule_type);
        deleteButton = itemView.findViewById(R.id.delete_button);
        strictRuleCheckbox = itemView.findViewById(R.id.strict_rule_checkbox);
        strictBadge = itemView.findViewById(R.id.strict_badge);
        deleteButton.setVisibility(isLocked ? View.GONE : View.VISIBLE);
    }
}
public class UrlListRecyclerAdapter extends RecyclerView.Adapter<UrlViewHolder> {

    Context context;
    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;

    public interface OnDeleteClickListener {
        void onDeleteClick(String url);
    }

    public interface OnStrictChangedListener {
        void onStrictChanged(String url, boolean strict);
    }

    private List<String> urlList;
    private final OnDeleteClickListener deleteListener;
    private final OnStrictChangedListener strictChangedListener;

    public UrlListRecyclerAdapter(
            List<String> urlList,
            OnDeleteClickListener listener,
            OnStrictChangedListener strictChangedListener) {
        this.urlList = urlList;
        this.deleteListener = listener;
        this.strictChangedListener = strictChangedListener;
    }

    @NonNull
    @Override
    public UrlViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(context);
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.custom_url_rule_item_modern, parent, false);
        return new UrlViewHolder(view, appPreferencesManagerSingleton.getIsBlockerActive());
    }


    @Override
    public void onBindViewHolder(@NonNull UrlViewHolder holder, int position) {

        String url = urlList.get(position);
        holder.textView.setText(url);
        if (url.toLowerCase(java.util.Locale.ROOT).startsWith("keyword:")) {
            holder.typeView.setText(R.string.url_rule_keyword);
        } else if (url.contains("/")) {
            holder.typeView.setText(R.string.url_rule_path);
        } else {
            holder.typeView.setText(R.string.url_rule_domain);
        }
        holder.strictRuleCheckbox.setOnCheckedChangeListener(null);
        boolean protectionActive = appPreferencesManagerSingleton.getIsBlockerActive();
        boolean strict = appPreferencesManagerSingleton.isStrictRestrictedUrlPattern(url);
        holder.strictRuleCheckbox.setChecked(strict);
        holder.strictRuleCheckbox.setEnabled(!protectionActive || !strict);
        holder.strictRuleCheckbox.setClickable(!protectionActive || !strict);
        holder.strictRuleCheckbox.setFocusable(!protectionActive || !strict);
        holder.strictRuleCheckbox.setText(protectionActive && strict
                ? R.string.strict_rule_locked_label : R.string.strict_rule_row_label);
        holder.strictBadge.setVisibility(holder.strictRuleCheckbox.isChecked()
                ? View.VISIBLE : View.GONE);
        holder.strictRuleCheckbox.setOnCheckedChangeListener((button, checked) -> {
            if (appPreferencesManagerSingleton.getIsBlockerActive() && !checked) {
                Toast.makeText(context, R.string.blocker_active_cannot_relax,
                        Toast.LENGTH_SHORT).show();
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition != RecyclerView.NO_POSITION) notifyItemChanged(adapterPosition);
                return;
            }
            strictChangedListener.onStrictChanged(url, checked);
            holder.strictBadge.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (appPreferencesManagerSingleton.getIsBlockerActive() && checked) {
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition != RecyclerView.NO_POSITION) notifyItemChanged(adapterPosition);
            }
        });
        holder.deleteButton.setOnClickListener(v -> {
            if(!appPreferencesManagerSingleton.getIsBlockerActive()){
                deleteListener.onDeleteClick(url);
            } else {
                Toast toast = Toast.makeText(context, R.string.blocker_active_cannot_remove, Toast.LENGTH_SHORT);
                toast.show();
            }

        });
    }

    @Override
    public int getItemCount() {
        return urlList.size();
    }

    public void updateList(List<String> newList) {
        int oldSize = urlList.size();
        this.urlList = new ArrayList<>(newList);
        // Strictness is separate state, so every retained row must be rebound even when
        // its URL identity did not change (for example, regular -> strictly forbidden).
        int retained = Math.min(oldSize, urlList.size());
        if (retained > 0) notifyItemRangeChanged(0, retained);
        if (urlList.size() > oldSize) {
            notifyItemRangeInserted(oldSize, urlList.size() - oldSize);
        } else if (oldSize > urlList.size()) {
            notifyItemRangeRemoved(urlList.size(), oldSize - urlList.size());
        }
    }


}
