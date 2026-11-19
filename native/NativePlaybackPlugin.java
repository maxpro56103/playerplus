package com.playerplus.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Мост между страницей (Player PLUS) и Android-плеером.
 * Методы: pickAudioFiles, setQueue, toggle, next, previous,
 * setShuffle, setRepeat, setVolume, seekTo. Событие: playbackState.
 */
@CapacitorPlugin(name = "NativePlayback")
public class NativePlaybackPlugin extends Plugin {
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaController controller;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (controller != null && controller.isPlaying()) emitState();
            main.postDelayed(this, 500);
        }
    };

    @Override
    public void load() {
        main.post(() -> {
            SessionToken token = new SessionToken(getContext(),
                    new ComponentName(getContext(), PlaybackService.class));
            final ListenableFuture<MediaController> future =
                    new MediaController.Builder(getContext(), token).buildAsync();
            future.addListener(() -> {
                try {
                    controller = future.get();
                    controller.addListener(new Player.Listener() {
                        @Override
                        public void onEvents(Player player, Player.Events events) {
                            emitState();
                        }
                    });
                    main.post(ticker);
                } catch (Exception ignored) {
                }
            }, ContextCompat.getMainExecutor(getContext()));
        });
    }

    @Override
    protected void handleOnDestroy() {
        main.removeCallbacks(ticker);
        if (controller != null) {
            controller.release();
            controller = null;
        }
    }

    private void emitState() {
        if (controller == null) return;
        JSObject s = new JSObject();
        s.put("currentIndex", controller.getCurrentMediaItemIndex());
        s.put("position", controller.getCurrentPosition());
        long d = controller.getDuration();
        s.put("duration", d < 0 ? 0 : d);
        s.put("isPlaying", controller.isPlaying());
        notifyListeners("playbackState", s);
    }

    /** Выполняет команду в главном потоке, когда плеер готов. */
    private void run(PluginCall call, Runnable r) {
        main.post(() -> {
            if (controller == null) {
                call.reject("Player is not ready yet");
                return;
            }
            try {
                r.run();
                call.resolve();
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        });
    }

    @PluginMethod
    public void setQueue(PluginCall call) {
        final JSArray items = call.getArray("items");
        final int start = call.getInt("startIndex", 0);
        if (items == null) {
            call.reject("items is required");
            return;
        }
        run(call, () -> {
            List<MediaItem> list = new ArrayList<>();
            int mappedStart = 0;
            try {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    String uri = o.optString("uri", "");
                    if (uri.isEmpty()) continue; // демо-треки без файла пропускаем
                    if (i < start) mappedStart++;
                    list.add(new MediaItem.Builder()
                            .setUri(Uri.parse(uri))
                            .setMediaMetadata(new MediaMetadata.Builder()
                                    .setTitle(o.optString("title", ""))
                                    .setArtist(o.optString("artist", ""))
                                    .build())
                            .build());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (list.isEmpty()) return;
            controller.setMediaItems(list, Math.min(mappedStart, list.size() - 1), 0);
            controller.prepare();
            controller.play();
        });
    }

    @PluginMethod
    public void toggle(PluginCall call) {
        run(call, () -> {
            if (controller.isPlaying()) {
                controller.pause();
            } else {
                if (controller.getPlaybackState() == Player.STATE_ENDED) controller.seekTo(0, 0);
                controller.prepare();
                controller.play();
            }
        });
    }

    @PluginMethod
    public void next(PluginCall call) {
        run(call, () -> {
            if (controller.hasNextMediaItem()) controller.seekToNextMediaItem();
            else controller.seekTo(0, 0);
            controller.play();
        });
    }

    @PluginMethod
    public void previous(PluginCall call) {
        run(call, () -> {
            if (controller.getCurrentPosition() > 3000 || !controller.hasPreviousMediaItem()) controller.seekTo(0);
            else controller.seekToPreviousMediaItem();
            controller.play();
        });
    }

    @PluginMethod
    public void setShuffle(PluginCall call) {
        final boolean on = call.getBoolean("enabled", false);
        run(call, () -> controller.setShuffleModeEnabled(on));
    }

    @PluginMethod
    public void setRepeat(PluginCall call) {
        final boolean on = call.getBoolean("enabled", false);
        run(call, () -> controller.setRepeatMode(on ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF));
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        final float v = call.getFloat("volume", 1f);
        run(call, () -> controller.setVolume(Math.max(0f, Math.min(1f, v))));
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        final double pos = call.getDouble("positionMs", 0.0);
        run(call, () -> controller.seekTo((long) pos));
    }

    // ---------- выбор файлов ----------

    @PluginMethod
    public void pickAudioFiles(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, i, "onPicked");
    }

    @ActivityCallback
    private void onPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        final JSObject out = new JSObject();
        final JSArray arr = new JSArray();
        out.put("tracks", arr);
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null) {
            call.resolve(out);
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        new Thread(() -> {
            for (Uri u : uris) {
                try {
                    getContext().getContentResolver()
                            .takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                arr.put(describe(u));
            }
            call.resolve(out);
        }).start();
    }

    private JSObject describe(Uri u) {
        String title = null;
        String artist = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(getContext(), u);
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored2) {
            }
        }
        if (title == null || title.trim().isEmpty()) title = displayName(u);
        JSObject o = new JSObject();
        o.put("uri", u.toString());
        o.put("title", title);
        o.put("artist", artist == null ? "" : artist);
        return o;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContext().getContentResolver()
                .query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                int dot = n.lastIndexOf('.');
                return dot > 0 ? n.substring(0, dot) : n;
            }
        } catch (Exception ignored) {
        }
        return "Track";
    }
}
