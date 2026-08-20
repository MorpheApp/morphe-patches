package app.morphe.extension.music.patches.downloads;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/** Local catalogue with a compact player matching YouTube Music's stock bottom player. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener {
    private static final int WHITE = Color.rgb(245, 245, 245);
    private static final int SECONDARY = Color.rgb(180, 180, 180);
    private static final int PANEL = Color.rgb(18, 18, 18);

    private File musicRoot;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private SeekBar miniSeek;
    private LinearLayout miniPlayer;
    private LinearLayout songsList;
    private ArrayList<String> displayQueue;
    private boolean userSeeking;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        ScrollView scroll = new ScrollView(getActivity());
        songsList = new LinearLayout(getActivity());
        songsList.setOrientation(LinearLayout.VERTICAL);
        songsList.setPadding(dp(16), dp(10), dp(16), dp(10));
        scroll.addView(songsList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView heading = text("Offline songs", 22, WHITE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(8), 0, dp(14));
        songsList.addView(heading);
        populateSongs(songsList);

        miniPlayer = createStockMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, dp(68)));
        return root;
    }

    @Override public void onStart() { super.onStart(); OfflinePlaybackService.addListener(this); }
    @Override public void onStop() { OfflinePlaybackService.removeListener(this); super.onStop(); }

    private void populateSongs(LinearLayout list) {
        File[] files = audioFiles();
        List<OfflineCollection> collections = OfflineCollection.loadAll(musicRoot);
        if (files.length == 0 && collections.isEmpty()) {
            TextView empty = text("No downloads\nDownloaded tracks will appear here", 16, SECONDARY);
            empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(80), 0, 0); list.addView(empty); return;
        }

        Set<String> collectedIds = new HashSet<>();
        for (OfflineCollection collection : collections) {
            collectedIds.addAll(collection.videoIds());
            list.addView(collectionRow(collection));
        }
        for (File file : files) {
            OfflineTrack track = OfflineTrack.load(file);
            if (!collectedIds.contains(track.videoId())) list.addView(songRow(track));
        }
    }

    private View collectionRow(OfflineCollection collection) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(7), 0, dp(7));
        row.addView(artworkView(collection.artwork()), new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout labels = new LinearLayout(getActivity()); labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0); labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(collection.title(), 16, WHITE); title.setTypeface(Typeface.DEFAULT_BOLD); title.setSingleLine(true);
        String kind = collection.type().equals("album") ? "Album" : "Playlist";
        TextView detail = text(kind + " • " + collection.subtitle() + " • " +
                collection.videoIds().size() + " tracks", 13, SECONDARY); detail.setSingleLine(true);
        labels.addView(title); labels.addView(detail); row.addView(labels, new LinearLayout.LayoutParams(0, dp(56), 1));
        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24", "Azioni per " + collection.title());
        menu.setOnClickListener(v -> showCollectionMenu(menu, collection));
        row.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(52)));
        row.setOnClickListener(v -> showCollection(collection));
        return row;
    }

    private void showCollectionMenu(View anchor, OfflineCollection collection) {
        PopupMenu popup = new PopupMenu(new ContextThemeWrapper(getActivity(), android.R.style.Theme_Material), anchor);
        popup.getMenu().add("Open");
        popup.getMenu().add("Delete " + (collection.type().equals("album") ? "album" : "playlist"));
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().toString().startsWith("Delete")) confirmDeleteCollection(collection);
            else showCollection(collection);
            return true;
        });
        popup.show();
    }

    private void confirmDeleteCollection(OfflineCollection collection) {
        new AlertDialog.Builder(getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Delete " + collection.title() + "?")
                .setMessage("All downloaded tracks in this collection will be removed from the device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteCollection(collection))
                .show();
    }

    private void deleteCollection(OfflineCollection collection) {
        for (String videoId : collection.videoIds()) {
            if (OfflineCollection.referencedByOtherCollection(musicRoot, videoId, collection.id())) continue;
            File audio = findAudio(videoId);
            if (audio != null) audio.delete();
            new File(musicRoot, videoId + ".json").delete();
            new File(musicRoot, videoId + ".jpg").delete();
            new File(musicRoot, videoId + ".webm.part").delete();
            new File(musicRoot, videoId + ".m4a.part").delete();
        }
        collection.metadataFile().delete();
        collection.artworkFile().delete();
        displayQueue = null;
        songsList.removeAllViews();
        TextView heading = text("Offline songs", 22, WHITE);
        heading.setTypeface(Typeface.DEFAULT_BOLD); heading.setPadding(0, dp(8), 0, dp(14));
        songsList.addView(heading); populateSongs(songsList);
        Utils.showToastShort("Collection deleted");
    }

    private void showCollection(OfflineCollection collection) {
        displayQueue = new ArrayList<>();
        for (String videoId : collection.videoIds()) {
            File audio = findAudio(videoId);
            if (audio != null) displayQueue.add(audio.getAbsolutePath());
        }
        while (songsList.getChildCount() > 0) songsList.removeViewAt(0);
        TextView back = text("‹  " + collection.title(), 22, WHITE);
        back.setTypeface(Typeface.DEFAULT_BOLD); back.setPadding(0, dp(8), 0, dp(14));
        back.setOnClickListener(v -> {
            displayQueue = null;
            songsList.removeAllViews();
            TextView heading = text("Offline songs", 22, WHITE);
            heading.setTypeface(Typeface.DEFAULT_BOLD); heading.setPadding(0, dp(8), 0, dp(14));
            songsList.addView(heading); populateSongs(songsList);
        });
        songsList.addView(back);
        for (String videoId : collection.videoIds()) {
            File audio = findAudio(videoId);
            if (audio != null) songsList.addView(songRow(OfflineTrack.load(audio)));
        }
    }

    private File findAudio(String videoId) {
        File webm = new File(musicRoot, videoId + ".webm");
        if (webm.isFile()) return webm;
        File m4a = new File(musicRoot, videoId + ".m4a");
        return m4a.isFile() ? m4a : null;
    }

    private View songRow(OfflineTrack track) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(7), 0, dp(7));
        ImageView art = artworkView(track.artwork());
        row.addView(art, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(track.displayTitle(), 16, WHITE); name.setSingleLine(true);
        TextView detail = text(track.displayArtist() + " • " + formatSize(track.audioFile().length()), 13, SECONDARY);
        labels.addView(name); labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24", "Azioni per " + track.displayTitle());
        menu.setOnClickListener(v -> showTrackMenu(menu, track));
        row.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(52)));
        row.setOnClickListener(v -> play(track));
        return row;
    }

    private LinearLayout createStockMiniPlayer() {
        LinearLayout outer = new LinearLayout(getActivity());
        outer.setOrientation(LinearLayout.VERTICAL); outer.setBackgroundColor(Color.BLACK);
        LinearLayout line = new LinearLayout(getActivity()); line.setGravity(Gravity.CENTER_VERTICAL);
        line.setTranslationY(-dp(7));
        line.setPadding(dp(16), 0, 0, 0);
        miniArtwork = artworkView(null); line.addView(miniArtwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(getActivity()); labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(16), 0, dp(4), 0);
        miniTitle = text("", 14, WHITE); miniTitle.setSingleLine(true); miniTitle.setTypeface(Typeface.DEFAULT_BOLD);
        miniArtist = text("", 14, SECONDARY); miniArtist.setSingleLine(true);
        labels.addView(miniTitle); labels.addView(miniArtist);
        line.addView(labels, new LinearLayout.LayoutParams(0, dp(67), 1));

        miniPrevious = icon("yt_fill_experimental_skip_previous_vd_theme_24", "Previous");
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        line.addView(miniPrevious, new LinearLayout.LayoutParams(dp(48), dp(67)));
        miniPlay = icon("yt_fill_experimental_play_vd_theme_24", "Play o pausa");
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        line.addView(miniPlay, new LinearLayout.LayoutParams(dp(48), dp(67)));
        miniNext = icon("yt_fill_experimental_skip_next_vd_theme_24", "Next");
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        line.addView(miniNext, new LinearLayout.LayoutParams(dp(48), dp(67)));
        outer.addView(line, new LinearLayout.LayoutParams(-1, dp(67)));

        miniSeek = new SeekBar(getActivity()); miniSeek.setPadding(0, 0, 0, 0);
        miniSeek.setMinHeight(dp(1)); miniSeek.setMaxHeight(dp(1));
        miniSeek.setThumbTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        miniSeek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        miniSeek.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(85, 85, 85)));
        miniSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
            public void onStopTrackingTouch(SeekBar bar) { userSeeking = false; OfflinePlaybackService.seekTo(bar.getProgress()); }
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {}
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, dp(24));
        miniSeek.setTranslationY(-dp(19));
        outer.addView(miniSeek, seekParams);
        return outer;
    }

    private void showTrackMenu(View anchor, OfflineTrack track) {
        PopupMenu popup = new PopupMenu(new ContextThemeWrapper(getActivity(), android.R.style.Theme_Material), anchor);
        popup.getMenu().add("Play");
        popup.getMenu().add("Delete download");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().toString().startsWith("Delete")) confirmDelete(track);
            else play(track);
            return true;
        });
        popup.show();
    }

    private void confirmDelete(OfflineTrack track) {
        new AlertDialog.Builder(getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Delete download?")
                .setMessage(track.displayTitle() + " will be removed from the device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteTrack(track))
                .show();
    }

    private void deleteTrack(OfflineTrack track) {
        File parent = track.audioFile().getParentFile();
        boolean deleted = track.audioFile().delete();
        new File(parent, track.videoId() + ".json").delete();
        track.artworkFile().delete();
        new File(parent, track.videoId() + ".webm.part").delete();
        new File(parent, track.videoId() + ".m4a.part").delete();
        OfflineCollection.removeTrackFromAll(parent, track.videoId());
        if (!deleted) {
            Utils.showToastShort("Could not delete download");
            return;
        }
        while (songsList.getChildCount() > 1) songsList.removeViewAt(1);
        populateSongs(songsList);
        Utils.showToastShort("Download deleted");
    }

    private void play(OfflineTrack track) {
        try {
            ArrayList<String> queue;
            if (displayQueue != null) queue = new ArrayList<>(displayQueue);
            else {
                File[] files = audioFiles();
                queue = new ArrayList<>(files.length);
                for (File file : files) queue.add(file.getAbsolutePath());
            }
            int queueIndex = Math.max(0, queue.indexOf(track.audioFile().getAbsolutePath()));
            Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                    .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                    .putExtra(OfflinePlaybackService.EXTRA_PATH, track.audioFile().getAbsolutePath())
                    .putExtra(OfflinePlaybackService.EXTRA_TITLE, track.displayTitle())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTIST, track.displayArtist())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH, track.artworkFile().getAbsolutePath())
                    .putStringArrayListExtra(OfflinePlaybackService.EXTRA_QUEUE, queue)
                    .putExtra(OfflinePlaybackService.EXTRA_QUEUE_INDEX, queueIndex);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getActivity().startForegroundService(intent);
            else getActivity().startService(intent);
            applyTrackVisuals(track);
        } catch (Exception ex) {
            Logger.printException(() -> "Offline playback failed: " + track.audioFile(), ex);
            Utils.showToastShort("Could not play download");
        }
    }

    @Override
    public void onPlaybackChanged(String title, boolean playing, int position, int duration) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (miniPlayer == null) return;
            miniPlayer.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
            OfflineTrack track = findByTitle(title);
            if (track != null) {
                applyTrackVisuals(track);
                if (displayQueue != null) {
                    int index = displayQueue.indexOf(track.audioFile().getAbsolutePath());
                    setButtonEnabled(miniPrevious, index > 0);
                    setButtonEnabled(miniNext, index >= 0 && index + 1 < displayQueue.size());
                } else {
                    File[] files = audioFiles();
                    int index = Arrays.asList(files).indexOf(track.audioFile());
                    setButtonEnabled(miniPrevious, index > 0);
                    setButtonEnabled(miniNext, index >= 0 && index + 1 < files.length);
                }
            } else miniTitle.setText(title);
            miniPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                    ? "yt_fill_experimental_pause_vd_theme_24"
                    : "yt_fill_experimental_play_vd_theme_24"));
            miniSeek.setMax(Math.max(1, duration));
            if (!userSeeking) miniSeek.setProgress(position);
        });
    }

    private void applyTrackVisuals(OfflineTrack track) {
        miniTitle.setText(track.displayTitle()); miniArtist.setText(track.displayArtist());
        Bitmap bitmap = track.artwork();
        if (bitmap != null) { miniArtwork.clearColorFilter(); miniArtwork.setPadding(0,0,0,0); miniArtwork.setImageBitmap(bitmap); miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP); }
    }

    private OfflineTrack findByTitle(String title) {
        for (File file : audioFiles()) { OfflineTrack track = OfflineTrack.load(file); if (track.displayTitle().equals(title)) return track; }
        return null;
    }

    private File[] audioFiles() {
        File[] files = musicRoot.listFiles(file -> file.isFile() && (file.getName().endsWith(".webm") || file.getName().endsWith(".m4a")));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator
                .comparing((File file) -> {
                    String album = OfflineTrack.load(file).album();
                    return album.isBlank() ? "~Tracks" : album.toLowerCase(Locale.ROOT);
                })
                .thenComparing(file -> OfflineTrack.load(file).displayTitle().toLowerCase(Locale.ROOT)));
        return files;
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }

    private ImageView artworkView(Bitmap bitmap) {
        ImageView view = new ImageView(getActivity()); view.setBackgroundColor(Color.rgb(40,40,40));
        if (bitmap != null) { view.setImageBitmap(bitmap); view.setScaleType(ImageView.ScaleType.CENTER_CROP); }
        else { view.setImageDrawable(ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24")); view.setColorFilter(WHITE); view.setPadding(dp(16),dp(16),dp(16),dp(16)); }
        return view;
    }
    private TextView text(String value, int sp, int color) { TextView v=new TextView(getActivity()); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private ImageButton icon(String drawable, String desc) { ImageButton b=new ImageButton(getActivity()); b.setImageDrawable(ResourceUtils.getDrawable(drawable)); b.setContentDescription(desc); b.setColorFilter(WHITE); b.setScaleType(ImageView.ScaleType.CENTER); b.setPadding(dp(10),dp(10),dp(10),dp(10)); b.setBackgroundColor(Color.TRANSPARENT); return b; }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+.5f); }
    private static String formatSize(long bytes) { return String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0); }
}
