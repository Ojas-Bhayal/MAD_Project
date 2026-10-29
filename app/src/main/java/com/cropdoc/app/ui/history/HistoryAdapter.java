package com.cropdoc.app.ui.history;

import android.util.Base64;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.cropdoc.app.R;
import com.cropdoc.app.databinding.ItemHistoryScanBinding;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.ui.common.UiMotionHelper;
import com.cropdoc.app.util.DiseaseLabelFormatter;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * RecyclerView ListAdapter rendering cached leaf scan history cards with top-aligned thumbnails,
 * localized disease titles, tactile press feedback, and non-blocking entrance stagger.
 */
public final class HistoryAdapter
        extends ListAdapter<ScanRecord, HistoryAdapter.ScanViewHolder> {

    public interface OnScanClickListener {
        void onScanClicked(@NonNull ScanRecord record);
    }

    private static final DiffUtil.ItemCallback<ScanRecord> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<ScanRecord>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull ScanRecord oldItem,
                        @NonNull ScanRecord newItem) {
                    return oldItem.getScanId().equals(newItem.getScanId());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull ScanRecord oldItem,
                        @NonNull ScanRecord newItem) {
                    return oldItem.isSynced() == newItem.isSynced()
                            && Float.compare(oldItem.getConfidence(), newItem.getConfidence()) == 0
                            && oldItem.getDiseaseId().equals(newItem.getDiseaseId())
                            && Objects.equals(oldItem.getOutcome(), newItem.getOutcome())
                            && Objects.equals(
                                    oldItem.getUserFeedbackHelpful(),
                                    newItem.getUserFeedbackHelpful());
                }
            };

    @NonNull
    private final OnScanClickListener clickListener;

    public HistoryAdapter(@NonNull OnScanClickListener clickListener) {
        super(DIFF_CALLBACK);
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public ScanViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHistoryScanBinding binding = ItemHistoryScanBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        UiMotionHelper.attachPressFeedback(binding.getRoot());
        return new ScanViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ScanViewHolder holder, int position) {
        holder.bind(getItem(position), clickListener);
        UiMotionHelper.animateListStagger(holder.itemView, position);
    }

    static final class ScanViewHolder extends RecyclerView.ViewHolder {
        private final ItemHistoryScanBinding binding;

        ScanViewHolder(@NonNull ItemHistoryScanBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Nullable
        private static byte[] decodeRemoteThumbnail(@Nullable String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            try {
                String encoded = value;
                int comma = value.indexOf(',');
                if (value.startsWith("data:") && comma >= 0) {
                    encoded = value.substring(comma + 1);
                }
                byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                return decoded.length == 0 ? null : decoded;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        void bind(@NonNull ScanRecord record, @NonNull OnScanClickListener listener) {
            binding.txtItemDisease.setText(
                    DiseaseLabelFormatter.formatDiseaseTitle(
                            record.getCropId(), record.getDiseaseId()));
            int pct = Math.max(0, Math.min(100, Math.round(record.getConfidence() * 100f)));
            binding.txtItemConfidence.setText(
                    binding.getRoot().getContext().getString(R.string.confidence_format, pct));
            String formattedDate = DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                    .format(new Date(record.getTimestampMs()));
            binding.txtItemTimestamp.setText(formattedDate);
            binding.txtSyncBadge.setText(
                    record.isSynced() ? R.string.status_synced : R.string.status_saved_offline);
            File imgFile = new File(record.getLocalImagePath());
            if (!record.getLocalImagePath().isEmpty() && imgFile.exists()) {
                Glide.with(binding.imgLeafThumb)
                        .load(imgFile)
                        .placeholder(R.drawable.ic_leaf_scan)
                        .error(R.drawable.ic_leaf_scan)
                        .centerCrop()
                        .into(binding.imgLeafThumb);
            } else {
                byte[] remoteThumbnail = decodeRemoteThumbnail(record.getRemoteImageUrl());
                if (remoteThumbnail != null) {
                    Glide.with(binding.imgLeafThumb)
                            .load(remoteThumbnail)
                            .placeholder(R.drawable.ic_leaf_scan)
                            .error(R.drawable.ic_leaf_scan)
                            .centerCrop()
                            .into(binding.imgLeafThumb);
                } else {
                    Glide.with(binding.imgLeafThumb).clear(binding.imgLeafThumb);
                    binding.imgLeafThumb.setImageResource(R.drawable.ic_leaf_scan);
                }
            }
            binding.getRoot().setOnClickListener(v -> listener.onScanClicked(record));
        }
    }
}
