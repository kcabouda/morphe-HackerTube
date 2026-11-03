package app.morphe.extension.youtube.bespoke;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The feed memory: likes, saves, hidden and watched videos.
 * Kept only on the device, in the app's private files, as one small JSON file
 * (the same shape the export/import file will use).
 * All methods are called on the main thread; writes happen in the background.
 */
final class FeedMemory {

    static final String LIKED = "liked";
    static final String SAVED = "saved";
    static final String HIDDEN = "hidden";
    static final String WATCHED = "watched";
    /** Furthest point reached in a video, in percent (not a time). */
    static final String PROGRESS = "progress";

    private static final String[] LISTS = {LIKED, SAVED, HIDDEN, WATCHED, PROGRESS};
    private static final String FILE_NAME = "bespoke_feed_memory.json";

    private static FeedMemory instance;

    /** List name -> (video id -> time added), in insertion order. */
    private final Map<String, LinkedHashMap<String, Long>> lists = new LinkedHashMap<>();
    private final File file;

    static FeedMemory get(Context context) {
        if (instance == null) instance = new FeedMemory(context.getApplicationContext());
        return instance;
    }

    private FeedMemory(Context context) {
        file = new File(context.getFilesDir(), FILE_NAME);
        for (String name : LISTS) lists.put(name, new LinkedHashMap<>());
        try {
            if (file.exists()) {
                JSONObject root = new JSONObject(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (String name : LISTS) {
                    JSONObject entries = root.optJSONObject(name);
                    if (entries == null) continue;
                    // Sort by time so the lists keep their order.
                    List<Map.Entry<String, Long>> sorted = new ArrayList<>();
                    for (Iterator<String> it = entries.keys(); it.hasNext(); ) {
                        String id = it.next();
                        sorted.add(new AbstractMap.SimpleEntry<>(id, entries.optLong(id)));
                    }
                    sorted.sort(Map.Entry.comparingByValue());
                    LinkedHashMap<String, Long> list = lists.get(name);
                    for (Map.Entry<String, Long> e : sorted) list.put(e.getKey(), e.getValue());
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Bespoke feed memory could not be read", ex);
        }
    }

    boolean contains(String list, String videoId) {
        return lists.get(list).containsKey(videoId);
    }

    int size(String list) {
        return lists.get(list).size();
    }

    /** Video ids of the list, most recent first. */
    List<String> ids(String list) {
        List<String> ids = new ArrayList<>(lists.get(list).keySet());
        Collections.reverse(ids);
        return ids;
    }

    /** @return true if the video is now in the list. */
    boolean toggle(String list, String videoId) {
        LinkedHashMap<String, Long> entries = lists.get(list);
        boolean added;
        if (entries.remove(videoId) != null) {
            added = false;
        } else {
            entries.put(videoId, System.currentTimeMillis());
            added = true;
        }
        save();
        return added;
    }

    void add(String list, String videoId) {
        LinkedHashMap<String, Long> entries = lists.get(list);
        entries.remove(videoId); // Move to the end (most recent).
        entries.put(videoId, System.currentTimeMillis());
        save();
    }

    /** @return furthest point reached in percent, or -1 if not known. */
    int progress(String videoId) {
        Long value = lists.get(PROGRESS).get(videoId);
        return value == null ? -1 : value.intValue();
    }

    /**
     * Records playback of a catalog video. Counts it as watched after 30 seconds or 20%,
     * and saves the progress when it moved by at least 5%.
     */
    void recordPlayback(String videoId, int percent, long timeMillis) {
        boolean changed = false;
        LinkedHashMap<String, Long> watched = lists.get(WATCHED);
        if ((timeMillis >= 30_000 || percent >= 20) && !watched.containsKey(videoId)) {
            watched.put(videoId, System.currentTimeMillis());
            changed = true;
        }
        LinkedHashMap<String, Long> progress = lists.get(PROGRESS);
        Long old = progress.get(videoId);
        if (old == null || percent >= old + 5 || (percent >= 95 && old < 95)) {
            if (old == null || percent > old) {
                progress.put(videoId, (long) percent);
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Empties every list. */
    void reset() {
        for (LinkedHashMap<String, Long> list : lists.values()) list.clear();
        save();
    }

    /**
     * Imports an exported file.
     *
     * @param replace true to replace the current memory, false to merge with it
     *                (a video keeps the latest time it was added).
     * @return number of entries in the file.
     */
    int importJson(String json, boolean replace) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!"bespoke-feed".equals(root.optString("format"))) {
            throw new IllegalArgumentException("Not a Bespoke feed export");
        }
        // Parse everything before changing anything.
        Map<String, List<Map.Entry<String, Long>>> parsed = new LinkedHashMap<>();
        int count = 0;
        for (String name : LISTS) {
            List<Map.Entry<String, Long>> entries = new ArrayList<>();
            JSONObject list = root.optJSONObject(name);
            if (list != null) {
                for (Iterator<String> it = list.keys(); it.hasNext(); ) {
                    String id = it.next();
                    if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
                    entries.add(new AbstractMap.SimpleEntry<>(id, list.optLong(id)));
                }
            }
            count += entries.size();
            parsed.put(name, entries);
        }
        for (String name : LISTS) {
            LinkedHashMap<String, Long> merged = new LinkedHashMap<>();
            List<Map.Entry<String, Long>> all = new ArrayList<>();
            if (!replace) all.addAll(lists.get(name).entrySet());
            all.addAll(parsed.get(name));
            Map<String, Long> latest = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : all) {
                Long current = latest.get(e.getKey());
                if (current == null || e.getValue() > current) latest.put(e.getKey(), e.getValue());
            }
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(latest.entrySet());
            sorted.sort(Map.Entry.comparingByValue());
            for (Map.Entry<String, Long> e : sorted) merged.put(e.getKey(), e.getValue());
            lists.put(name, merged);
        }
        save();
        return count;
    }

    String toJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "bespoke-feed");
        root.put("version", 1);
        root.put("exported_at", System.currentTimeMillis());
        for (String name : LISTS) {
            JSONObject entries = new JSONObject();
            for (Map.Entry<String, Long> e : lists.get(name).entrySet()) {
                entries.put(e.getKey(), e.getValue());
            }
            root.put(name, entries);
        }
        return root.toString();
    }

    private void save() {
        final String json;
        try {
            json = toJson();
        } catch (Exception ex) {
            Logger.printException(() -> "Bespoke feed memory could not be serialized", ex);
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            synchronized (FeedMemory.class) {
                try {
                    File tmp = new File(file.getPath() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    if (!tmp.renameTo(file)) throw new IllegalStateException("rename failed");
                } catch (Exception ex) {
                    Logger.printException(() -> "Bespoke feed memory could not be saved", ex);
                }
            }
        });
    }
}
