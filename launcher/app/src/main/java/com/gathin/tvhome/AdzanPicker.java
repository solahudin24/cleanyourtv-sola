package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Suara adzan dari file di TV: lists the audio files Android knows about (Download, Music, a USB stick),
 * plays the first ten seconds of one on OK, and on a second OK copies it next to the launcher's other
 * files as adzan.mp3 or adzan_subuh.mp3, so the stick can come out afterwards. The same slot adb push fills.
 */
final class AdzanPicker {

    static final String PREF_NAME = "adzan_file_name";
    static final String PREF_NAME_SUBUH = "adzan_subuh_file_name";
    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final int PREVIEW_MS = 10_000;
    private static final int MAX_ITEMS = 300;

    private AdzanPicker() {
    }

    static String permission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** What the row in Launcher settings shows for this slot. */
    static String currentName(Context c, boolean subuh) {
        File dir = Config.dir(c);
        File f = dir == null ? null : new File(dir, subuh ? Adzan.FILE_SUBUH : Adzan.FILE);
        if (f == null || !f.isFile()) return subuh ? "sama dengan adzan biasa" : "belum ada (pakai nada)";
        String name = Adzan.prefs(c).getString(subuh ? PREF_NAME_SUBUH : PREF_NAME, "");
        return name.isEmpty() ? f.getName() + " (dari adb)" : name;
    }

    static void show(Activity activity, boolean subuh, Runnable onChanged) {
        if (activity.checkSelfPermission(permission()) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{permission()}, 7);
            Toast.makeText(activity, "Izinkan akses audio, lalu pilih lagi", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Mencari file audio di TV…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            List<Track> tracks;
            try {
                tracks = find(activity);
            } catch (Exception e) {
                tracks = null;
            }
            final List<Track> found = tracks;
            main.post(() -> {
                if (activity.isFinishing()) return;
                if (found == null) {
                    Toast.makeText(activity, "Gagal membaca file audio di TV", Toast.LENGTH_LONG).show();
                } else {
                    choose(activity, subuh, found, onChanged);
                }
            });
        }, "adzan-files");
        t.setDaemon(true);
        t.start();
    }

    private static final class Track {
        final Uri uri;
        final String name;
        final long durationMs;
        final long bytes;
        final boolean usb;

        Track(Uri uri, String name, long durationMs, long bytes, boolean usb) {
            this.uri = uri;
            this.name = name;
            this.durationMs = durationMs;
            this.bytes = bytes;
            this.usb = usb;
        }

        String label() {
            long s = durationMs / 1000;
            String length = durationMs > 0 ? String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) : "?:??";
            return name + "\n" + length + "  \u00B7  " + (usb ? "USB" : "TV")
                    + (bytes > MAX_BYTES ? "  \u00B7  terlalu besar" : "");
        }
    }

    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac", ".amr"};
    private static final String[] FOLDERS = {"Download", "Downloads", "Music", "Audio", "Documents", "Ringtones",
            "Notifications", "Movies", "Bluetooth", "Received", "Xender", "SHAREit"};

    /**
     * Files the catalogue may not know yet: one just sent to the TV is often listed only after the next
     * media scan or a reboot. Walks the usual folders of the TV's storage and any USB stick, asks Android
     * to catalogue what it finds, then reads the catalogue and adds anything still missing straight from the file.
     */
    private static List<Track> find(Context context) {
        List<File> files = new ArrayList<>();
        File primary = android.os.Environment.getExternalStorageDirectory();
        walk(primary, 0, 1, files);
        for (String folder : FOLDERS) walk(new File(primary, folder), 0, 3, files);
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File v : volumes) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self") || !v.isDirectory()) continue;
                walk(v, 0, 3, files);
            }
        }
        if (!files.isEmpty()) {
            String[] paths = new String[files.size()];
            for (int i = 0; i < paths.length; i++) paths[i] = files.get(i).getAbsolutePath();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(paths.length);
            try {
                android.media.MediaScannerConnection.scanFile(context, paths, null, (path, uri) -> done.countDown());
                done.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // a missing scan only means the files come from the folder walk below
            }
        }
        List<Track> out;
        try {
            out = query(context.getContentResolver());
        } catch (Exception e) {
            out = new ArrayList<>();
        }
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (Track t : out) listed.add(t.name.toLowerCase(Locale.ROOT) + "|" + t.bytes);
        String primaryPath = primary.getAbsolutePath();
        for (File f : files) {
            if (out.size() >= MAX_ITEMS) break;
            if (listed.contains(f.getName().toLowerCase(Locale.ROOT) + "|" + f.length())) continue;
            out.add(new Track(Uri.fromFile(f), f.getName(), duration(f), f.length(),
                    !f.getAbsolutePath().startsWith(primaryPath)));
        }
        return out;
    }

    private static void walk(File dir, int depth, int maxDepth, List<File> out) {
        if (dir == null || depth > maxDepth || out.size() >= MAX_ITEMS) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (out.size() >= MAX_ITEMS) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < maxDepth && !n.equals("Android")) walk(f, depth + 1, maxDepth, out);
            } else if (isAudio(n) && !out.contains(f)) {
                out.add(f);
            }
        }
    }

    private static boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_EXTENSIONS) if (lower.endsWith(ext)) return true;
        return false;
    }

    private static long duration(File f) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // nothing held
            }
        }
    }

    private static List<Track> query(ContentResolver resolver) {
        boolean modern = Build.VERSION.SDK_INT >= 29;
        Uri collection = modern ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String place = modern ? MediaStore.MediaColumns.VOLUME_NAME : MediaStore.MediaColumns.DATA;
        String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION, MediaStore.MediaColumns.SIZE, place};
        List<Track> out = new ArrayList<>();
        try (Cursor c = resolver.query(collection, columns, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < MAX_ITEMS) {
                long id = c.getLong(0);
                String name = c.getString(1);
                String where = c.getString(4);
                boolean usb = where != null && (modern
                        ? !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(where)
                        : !where.startsWith("/storage/emulated") && !where.startsWith("/sdcard"));
                out.add(new Track(ContentUris.withAppendedId(collection, id), name == null ? "audio" : name,
                        c.getLong(2), c.getLong(3), usb));
            }
        }
        return out;
    }

    private static void choose(Activity activity, boolean subuh, List<Track> tracks, Runnable onChanged) {
        String title = subuh ? "Suara adzan Subuh" : "Suara adzan";
        AlertDialog.Builder b = new AlertDialog.Builder(activity).setTitle(title)
                .setNegativeButton(android.R.string.cancel, null);
        File dir = Config.dir(activity);
        File current = dir == null ? null : new File(dir, subuh ? Adzan.FILE_SUBUH : Adzan.FILE);
        if (current != null && current.isFile()) {
            b.setNeutralButton("Hapus", (d, w) -> {
                if (current.delete()) {
                    Adzan.prefs(activity).edit().remove(subuh ? PREF_NAME_SUBUH : PREF_NAME).apply();
                    Toast.makeText(activity, subuh ? "Subuh kembali memakai adzan biasa"
                            : "Suara adzan dihapus, kembali ke nada", Toast.LENGTH_LONG).show();
                    onChanged.run();
                }
            });
        }
        if (tracks.isEmpty()) {
            b.setMessage("Tidak ada file audio di TV. Sudah dicari di penyimpanan TV (termasuk Download dan "
                    + "Music) dan flashdisk. Salin file MP3 ke folder Download, atau colok flashdisk berisi file "
                    + "adzan, lalu buka lagi. File yang baru dikirim langsung ikut dipindai.").show();
            return;
        }
        b.setMessage("OK: dengarkan 10 detik pertama. OK sekali lagi di file yang sama: pakai.");
        String[] labels = new String[tracks.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = tracks.get(i).label();
        ListView list = new ListView(activity);
        list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
        b.setView(list);
        AlertDialog dialog = b.create();

        Preview preview = new Preview(activity);
        dialog.setOnDismissListener(d -> preview.stop());
        list.setOnItemClickListener((parent, view, position, id) -> {
            Track track = tracks.get(position);
            if (track.bytes > MAX_BYTES) {
                Toast.makeText(activity, "File terlalu besar (maksimal 15 MB). Kecilkan dulu file-nya.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (preview.playing == position) {
                preview.stop();
                dialog.dismiss();
                install(activity, subuh, track, onChanged);
            } else {
                preview.play(position, track.uri);
            }
        });
        dialog.show();
        list.requestFocus();
    }

    /** Ten seconds of one file at a time. */
    private static final class Preview {
        private final Context context;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private MediaPlayer player;
        int playing = -1;

        Preview(Context context) {
            this.context = context;
        }

        void play(int position, Uri uri) {
            stop();
            playing = position;
            try {
                MediaPlayer p = new MediaPlayer();
                p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                p.setDataSource(context, uri);
                p.setOnPreparedListener(MediaPlayer::start);
                p.setOnErrorListener((mp, what, extra) -> {
                    Toast.makeText(context, "File ini tidak bisa diputar", Toast.LENGTH_SHORT).show();
                    stop();
                    return true;
                });
                p.prepareAsync();
                player = p;
                handler.postDelayed(this::silence, PREVIEW_MS);
                Toast.makeText(context, "Memutar 10 detik. Tekan OK lagi untuk memakai.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, "File ini tidak bisa diputar", Toast.LENGTH_SHORT).show();
                stop();
            }
        }

        /** The preview ran out: quiet, but the same file still counts as chosen for the second OK. */
        private void silence() {
            if (player != null) {
                player.release();
                player = null;
            }
        }

        void stop() {
            handler.removeCallbacksAndMessages(null);
            silence();
            playing = -1;
        }
    }

    private static void install(Activity activity, boolean subuh, Track track, Runnable onChanged) {
        File dir = Config.dir(activity);
        if (dir == null) {
            Toast.makeText(activity, "Penyimpanan launcher tidak tersedia", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Menyalin " + track.name + "…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            File target = new File(dir, subuh ? Adzan.FILE_SUBUH : Adzan.FILE);
            File temp = new File(dir, target.getName() + ".baru");
            boolean ok = false;
            try (InputStream in = activity.getContentResolver().openInputStream(track.uri);
                 OutputStream out = new FileOutputStream(temp)) {
                if (in != null) {
                    byte[] buf = new byte[64 * 1024];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) throw new java.io.IOException("too big");
                        out.write(buf, 0, n);
                    }
                    ok = total > 0;
                }
            } catch (Exception e) {
                ok = false;
            }
            ok = ok && (!target.exists() || target.delete()) && temp.renameTo(target);
            if (!ok) temp.delete();
            final boolean done = ok;
            main.post(() -> {
                if (done) {
                    Adzan.prefs(activity).edit().putString(subuh ? PREF_NAME_SUBUH : PREF_NAME, track.name).apply();
                    Toast.makeText(activity, "Suara adzan" + (subuh ? " Subuh" : "") + " diganti: " + track.name,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, "Gagal menyalin file", Toast.LENGTH_LONG).show();
                }
                onChanged.run();
            });
        }, "adzan-copy");
        t.setDaemon(true);
        t.start();
    }
}
