package com.cropdoc.app.ui.history;

import android.os.Bundle;
import android.util.Log;
import java.util.Collections;
import java.util.List;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.cropdoc.app.CropDocApplication;
import com.cropdoc.app.R;
import com.cropdoc.app.databinding.FragmentHistoryBinding;
import com.cropdoc.app.ui.common.UiMotionHelper;
import com.cropdoc.app.ui.common.ViewModelFactory;
import com.cropdoc.app.ui.result.ResultFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/**
 * Displays offline-cached scan history with pluralized scan counts, a helpful empty state
 * with a Scan CTA, and a confirmation-guarded DPDP Act data erasure button.
 */
public final class HistoryFragment extends Fragment {

    private static final String TAG = "CropDocCloudHistory";
    private int researchHistoryRequestVersion = 0;

    @Nullable
    private FragmentHistoryBinding binding;

    private HistoryViewModel viewModel;
    private HistoryAdapter adapter;
    private ResearchHistoryAdapter researchAdapter;
    private ResearchScanHistoryStore researchHistoryStore;
    private List<com.cropdoc.app.domain.model.ScanRecord> currentVerifiedRecords = Collections.emptyList();
    private List<ResearchScanHistoryStore.Entry> currentResearchRecords = Collections.emptyList();

    @NonNull
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewModelFactory factory = ((CropDocApplication) requireActivity().getApplication())
                .getAppContainer()
                .getViewModelFactory();
        viewModel = new ViewModelProvider(this, factory).get(HistoryViewModel.class);
        researchHistoryStore = new ResearchScanHistoryStore(requireContext().getApplicationContext());
        setupRecyclerView();
        setupResearchRecyclerView();
        setupButtons();
        observeHistoryList();
        observeCloudHistoryStatus();
        viewModel.refreshCloudHistory();
        refreshResearchHistory();
    }

    private void setupButtons() {
        if (binding == null) {
            return;
        }
        UiMotionHelper.attachPressFeedback(
                binding.btnEmptyScanCta, binding.btnEraseHistoryDpdp);
        binding.btnEmptyScanCta.setOnClickListener(v -> {
            if (isAdded()) {
                try {
                    NavHostFragment.findNavController(this).navigate(R.id.scanFragment);
                } catch (RuntimeException ignored) {
                    // Safe navigation guard
                }
            }
        });
        binding.btnEraseHistoryDpdp.setOnClickListener(v -> showConfirmDeleteHistoryDialog());
    }

    private void showConfirmDeleteHistoryDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm_delete_history_title)
                .setMessage(R.string.confirm_delete_history_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm_delete, (dialog, which) -> {
                    viewModel.deleteAllHistoryForCurrentUser();
                    if (researchHistoryStore != null) {
                        researchHistoryStore.clear();
                        currentResearchRecords = Collections.emptyList();
                        renderHistoryState();
                    }
                })
                .show();
    }

    private void observeHistoryList() {
        viewModel.observeHistory().observe(getViewLifecycleOwner(), records -> {
            if (binding == null || records == null) {
                return;
            }
            currentVerifiedRecords = records;
            renderHistoryState();
        });
    }

    private void observeCloudHistoryStatus() {
        viewModel.observeCloudHistoryStatus().observe(getViewLifecycleOwner(), message -> {
            if (binding == null || message == null || message.trim().isEmpty()) return;
            Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG).show();
        });
    }


    @Override
    public void onResume() {
        super.onResume();
        // Refresh cloud metadata whenever History is revisited, including after sign-in/network return.
        if (viewModel != null) {
            viewModel.refreshCloudHistory();
        }
        refreshResearchHistory();
    }

    private void refreshResearchHistory() {
        if (researchHistoryStore == null || binding == null) {
            return;
        }

        // Invalidate callbacks from previous refreshes, including refreshes for a former account.
        final int requestVersion = ++researchHistoryRequestVersion;
        researchHistoryStore.loadForCurrentUserAsync((entries, cloudLoaded, message) -> {
            if (!isAdded() || binding == null
                    || requestVersion != researchHistoryRequestVersion) {
                return;
            }

            currentResearchRecords = entries;
            renderHistoryState();

            int cloudBacked = 0;
            for (ResearchScanHistoryStore.Entry entry : entries) {
                if (entry.cloudBacked) {
                    cloudBacked++;
                }
            }
            if (cloudLoaded) {
                Log.i(TAG, "Research history refresh complete. entries=" + entries.size()
                        + ", cloudBacked=" + cloudBacked + ".");
            } else if (message != null && !message.trim().isEmpty()) {
                Log.w(TAG, "Research history cloud refresh unavailable: " + message);
                Snackbar.make(binding.getRoot(), "Research history: " + message,
                        Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void renderHistoryState() {
        if (binding == null) return;
        if (adapter != null) adapter.submitList(currentVerifiedRecords);
        if (researchAdapter != null) researchAdapter.submitList(currentResearchRecords);
        boolean noVerified = currentVerifiedRecords.isEmpty();
        boolean noResearch = currentResearchRecords.isEmpty();
        boolean allEmpty = noVerified && noResearch;
        binding.layoutEmptyHistory.setVisibility(allEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerHistory.setVisibility(noVerified ? View.GONE : View.VISIBLE);
        binding.txtResearchHistoryHeading.setVisibility(noResearch ? View.GONE : View.VISIBLE);
        binding.recyclerResearchHistory.setVisibility(noResearch ? View.GONE : View.VISIBLE);
        binding.btnEraseHistoryDpdp.setVisibility(allEmpty ? View.GONE : View.VISIBLE);
        binding.txtHistoryCount.setVisibility(allEmpty ? View.GONE : View.VISIBLE);
        if (!allEmpty) {
            int count = currentVerifiedRecords.size() + currentResearchRecords.size();
            binding.txtHistoryCount.setText(getResources().getQuantityString(
                    R.plurals.history_scan_count, count, count));
        }
    }

    private void setupResearchRecyclerView() {
        if (binding == null) return;
        researchAdapter = new ResearchHistoryAdapter();
        binding.recyclerResearchHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerResearchHistory.setAdapter(researchAdapter);
    }

    private void setupRecyclerView() {
        if (binding == null) {
            return;
        }
        adapter = new HistoryAdapter(record -> {
            if (!isAdded()) {
                return;
            }
            try {
                Bundle args = new Bundle();
                args.putString(ResultFragment.ARG_SCAN_ID, record.getScanId());
                NavHostFragment.findNavController(this).navigate(R.id.resultFragment, args);
            } catch (RuntimeException ignored) {
                // Safe navigation guard
            }
        });
        binding.recyclerHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerHistory.setAdapter(adapter);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
