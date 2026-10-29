package com.cropdoc.app.ui.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.cropdoc.app.R;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Renders AI-only scan attempts separately from verified production history. */
public final class ResearchHistoryAdapter extends RecyclerView.Adapter<ResearchHistoryAdapter.Holder> {
    private final List<ResearchScanHistoryStore.Entry> entries = new ArrayList<>();

    public void submitList(@NonNull List<ResearchScanHistoryStore.Entry> items) {
        entries.clear();
        entries.addAll(items);
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return entries.size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_research_scan_history, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        ResearchScanHistoryStore.Entry entry = entries.get(position);
        String crop = cropName(entry.cropKey);
        String status = entry.status;
        if ("RESULT".equals(status) && entry.classId != null) {
            holder.title.setText(crop + " — " + humanize(entry.classId));
            holder.details.setText(holder.itemView.getContext().getString(
                    R.string.research_history_result_detail,
                    Math.max(0, Math.min(100, Math.round(entry.modelScore * 100f))),
                    entry.inferenceTimeMs));
        } else {
            holder.title.setText(crop + " — " + statusName(status));
            holder.details.setText(entry.message.isEmpty()
                    ? holder.itemView.getContext().getString(R.string.research_history_no_prediction)
                    : entry.message);
        }
        String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                Locale.getDefault()).format(new Date(entry.timestampMs));
        holder.timestamp.setText(date);
        holder.badge.setText(R.string.research_history_badge);
        File image = new File(entry.imagePath);
        if (image.exists()) {
            Glide.with(holder.thumbnail).load(image).placeholder(R.drawable.ic_leaf_scan)
                    .error(R.drawable.ic_leaf_scan).centerCrop().into(holder.thumbnail);
        } else {
            Glide.with(holder.thumbnail).clear(holder.thumbnail);
            holder.thumbnail.setImageResource(R.drawable.ic_leaf_scan);
        }
        holder.itemView.setOnClickListener(v -> {
            String detail;
            if ("RESULT".equals(status) && entry.classId != null) {
                detail = holder.itemView.getContext().getString(
                        R.string.research_history_detail_dialog,
                        crop, humanize(entry.classId),
                        Math.max(0, Math.min(100, Math.round(entry.modelScore * 100f))),
                        date, entry.message == null ? "" : entry.message);
            } else {
                detail = holder.itemView.getContext().getString(
                        R.string.research_history_nonresult_dialog,
                        crop, statusName(status), date,
                        entry.message == null ? "" : entry.message);
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(holder.itemView.getContext())
                    .setTitle(R.string.research_history_badge)
                    .setMessage(detail)
                    .setPositiveButton(R.string.action_dismiss, null)
                    .show();
        });
    }

    private static String cropName(String key) {
        switch (key) {
            case "rice": return "Rice";
            case "groundnut": return "Groundnut";
            case "cotton": return "Cotton";
            default: return key;
        }
    }
    private static String statusName(String status) {
        if ("REJECTED".equals(status)) return "No prediction accepted";
        if ("UNAVAILABLE".equals(status)) return "Model unavailable";
        return "Scan recorded";
    }
    private static String humanize(String classId) {
        String[] words = classId.replace('_', ' ').split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView title;
        final TextView details;
        final TextView timestamp;
        final TextView badge;
        Holder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.img_research_history_thumb);
            title = itemView.findViewById(R.id.txt_research_history_title);
            details = itemView.findViewById(R.id.txt_research_history_details);
            timestamp = itemView.findViewById(R.id.txt_research_history_timestamp);
            badge = itemView.findViewById(R.id.txt_research_history_badge);
        }
    }
}
