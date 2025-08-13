package com.cappielloantonio.tempo.ui.fragment;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerLyricsBinding;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Line;
import com.cappielloantonio.tempo.subsonic.models.LyricsList;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;


import java.util.List;
import java.util.function.Supplier;


@OptIn(markerClass = UnstableApi.class)
public class PlayerLyricsFragment extends Fragment {
    private static final String TAG = "PlayerLyricsFragment";

    private InnerFragmentPlayerLyricsBinding bind;
    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;
    private MediaBrowser mediaBrowser;
    private Handler syncLyricsHandler;
    private Runnable syncLyricsRunnable;

    private boolean isUserTouching = false;

    private final Runnable resetTouchingStateRunnable = () -> isUserTouching = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = InnerFragmentPlayerLyricsBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        initOverlay();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initPanelContent();
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();

    }

    @Override
    public void onResume() {
        super.onResume();
        bindMediaController();
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPause() {
        super.onPause();
        releaseHandler();
        if (!Preferences.isDisplayAlwaysOn()) {
            requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onStop() {
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initOverlay() {
        bind.syncLyricsTapButton.setOnClickListener(view -> playerBottomSheetViewModel.changeSyncLyricsState());
        bind.nowPlayingSongLyricsRecyclerView.setOnTouchListener((View v, MotionEvent event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (!isUserTouching) isUserTouching = true;
                syncLyricsHandler.removeCallbacks(resetTouchingStateRunnable);
                syncLyricsHandler.postDelayed(resetTouchingStateRunnable, 2000);
            }
            return false;
        });

    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseHandler() {
        if (syncLyricsHandler != null) {
            syncLyricsHandler.removeCallbacks(syncLyricsRunnable);
            syncLyricsHandler = null;
        }
    }

    private void releaseBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                mediaBrowser = mediaBrowserListenableFuture.get();
                defineProgressHandler();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void initPanelContent() {
        if (OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable()) {
            playerBottomSheetViewModel.getLiveLyricsList().observe(getViewLifecycleOwner(), lyricsList -> {
                setPanelContent(null, lyricsList);
            });
        } else {
            playerBottomSheetViewModel.getLiveLyrics().observe(getViewLifecycleOwner(), lyrics -> {
                setPanelContent(lyrics, null);
            });
        }
    }

    static class LyricsAdapter extends RecyclerView.Adapter<LyricsAdapter.LyricsViewHolder> {

        private final List<Line> lyricsList;
        private int highlightedIndex = -2;

        private final Supplier<MediaBrowser> mediaBrowser;

        public LyricsAdapter(String s, Supplier<MediaBrowser> mb) {
            Line l = new Line();
            l.setValue(s);
            this.lyricsList = List.of(l);
            mediaBrowser = mb;
        }

        public LyricsAdapter(LyricsList lyricsList, Supplier<MediaBrowser> mb) {
            this.lyricsList = lyricsList.getStructuredLyrics().get(0).getLine();
            highlightedIndex = -1;
            mediaBrowser = mb;
        }

        public void setHighlightedIndex(int index) {
            if (index != highlightedIndex) {
                notifyItemChanged(highlightedIndex);
                notifyItemChanged(index);
                highlightedIndex = index;
            }
        }

        static class LyricsViewHolder extends RecyclerView.ViewHolder {
            TextView textView;

            LyricsViewHolder(TextView v) {
                super(v);
                textView = v;
            }
        }

        @NonNull
        @Override
        public LyricsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext(), null, 0, R.style.BodyLarge);
            return new LyricsViewHolder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull LyricsViewHolder holder, int lineIndex) {
            holder.textView.setText(lyricsList.get(lineIndex).value);
            holder.textView.setClickable(true);
            holder.textView.setOnClickListener(v -> {
                Integer target = lyricsList.get(holder.getBindingAdapterPosition()).getStart();
                if (target != null && mediaBrowser.get() != null) {
                    mediaBrowser.get().seekTo(target);
                }else {
                    Log.i(TAG, "mediaBrowser not found!");
                }
            });
            if (highlightedIndex != -2) {
                if (lineIndex == highlightedIndex) {
                    holder.textView.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.lyricsTextColor));
                    holder.textView.setTypeface(null, Typeface.BOLD);
                } else {
                    holder.textView.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.shadowsLyricsTextColor));
                    holder.textView.setTypeface(null, Typeface.BOLD);
                }
            }
        }

        @Override
        public int getItemCount() {
            return lyricsList.size();
        }
    }

    private void setPanelContent(String lyrics, LyricsList lyricsList) {
        playerBottomSheetViewModel.getLiveDescription().observe(getViewLifecycleOwner(), description -> {
            if (bind != null) {
                bind.nowPlayingSongLyricsRecyclerView.smoothScrollBy(0, 0);

                if (lyrics != null && !lyrics.trim().equals("")) {
                    bind.nowPlayingSongLyricsRecyclerView.setAdapter(new LyricsAdapter(lyrics,()->mediaBrowser));
                    bind.emptyDescriptionImageView.setVisibility(View.GONE);
                    bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
                    bind.syncLyricsTapButton.setVisibility(View.GONE);
                } else if (lyricsList != null && lyricsList.getStructuredLyrics() != null) {
                    setSyncLirics(lyricsList);
                    bind.nowPlayingSongLyricsRecyclerView.setVisibility(View.VISIBLE);
                    bind.emptyDescriptionImageView.setVisibility(View.GONE);
                    bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
                    bind.syncLyricsTapButton.setVisibility(View.VISIBLE);
                } else if (description != null && !description.trim().equals("")) {
                    bind.nowPlayingSongLyricsRecyclerView.setAdapter(new LyricsAdapter(lyrics, ()->mediaBrowser));
                    bind.nowPlayingSongLyricsRecyclerView.setVisibility(View.VISIBLE);
                    bind.emptyDescriptionImageView.setVisibility(View.GONE);
                    bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
                    bind.syncLyricsTapButton.setVisibility(View.GONE);
                } else {
                    bind.nowPlayingSongLyricsRecyclerView.setAdapter(null);
                    bind.nowPlayingSongLyricsRecyclerView.setVisibility(View.GONE);
                    bind.emptyDescriptionImageView.setVisibility(View.VISIBLE);
                    bind.titleEmptyDescriptionLabel.setVisibility(View.VISIBLE);
                    bind.syncLyricsTapButton.setVisibility(View.GONE);
                }
            }
        });
    }

    @SuppressLint("DefaultLocale")
    private void setSyncLirics(LyricsList lyricsList) {
        if (lyricsList.getStructuredLyrics() != null && !lyricsList.getStructuredLyrics().isEmpty() && lyricsList.getStructuredLyrics().get(0).getLine() != null) {
            bind.nowPlayingSongLyricsRecyclerView.setAdapter(new LyricsAdapter(lyricsList, ()->mediaBrowser));
        }
    }

    private void defineProgressHandler() {
        playerBottomSheetViewModel.getLiveLyricsList().observe(getViewLifecycleOwner(), lyricsList -> {
            if (lyricsList != null) {

                if (lyricsList.getStructuredLyrics() != null && lyricsList.getStructuredLyrics().get(0) != null && !lyricsList.getStructuredLyrics().get(0).getSynced()) {
                    releaseHandler();
                    return;
                }

                syncLyricsHandler = new Handler();
                syncLyricsRunnable = () -> {
                    if (syncLyricsHandler != null) {
                        if (bind != null) {
                            displaySyncedLyrics();
                        }

                        syncLyricsHandler.postDelayed(syncLyricsRunnable, 250);
                    }
                };

                syncLyricsHandler.postDelayed(syncLyricsRunnable, 250);
            } else {
                releaseHandler();
            }
        });
    }

    private void displaySyncedLyrics() {
        LyricsList lyricsList = playerBottomSheetViewModel.getLiveLyricsList().getValue();
        int timestamp = (int) (mediaBrowser.getCurrentPosition());

        if (lyricsList != null && lyricsList.getStructuredLyrics() != null && !lyricsList.getStructuredLyrics().isEmpty() && lyricsList.getStructuredLyrics().get(0).getLine() != null) {
            List<Line> lines = lyricsList.getStructuredLyrics().get(0).getLine();

            if (lines == null || lines.isEmpty()) return;

            int index = 0;
            int lineIndexToHighlight = -1;
            while (true) {
                if (index == lines.size()) {
                    lineIndexToHighlight = lines.size() - 1;
                    break;
                }
                Line line = lines.get(index);
                if (line != null && line.getStart() != null) {
                    if (timestamp < line.getStart()) {
                        lineIndexToHighlight = index - 1;
                        break;
                    }
                }
                index++;
            }

            LyricsAdapter adapter = (LyricsAdapter) bind.nowPlayingSongLyricsRecyclerView.getAdapter();
            if (adapter != null) {
                adapter.setHighlightedIndex(lineIndexToHighlight);
            }

            if (lineIndexToHighlight > -1 && playerBottomSheetViewModel.getSyncLyricsState() && !isUserTouching) {
                LinearLayoutManager layoutManager = (LinearLayoutManager) bind.nowPlayingSongLyricsRecyclerView.getLayoutManager();
                assert layoutManager != null;
                layoutManager.scrollToPositionWithOffset(Math.max(lineIndexToHighlight, 0), bind.nowPlayingSongLyricsRecyclerView.getHeight() / 2);
            }
        }
    }
}