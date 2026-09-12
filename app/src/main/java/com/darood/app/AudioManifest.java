package com.darood.app;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Manifest revisions describe the document; only Item.version describes downloaded audio. */
final class AudioManifest {
    static final String URL = "https://kkfeoxyxbucbicsnpfwe.supabase.co/storage/v1/object/public/audio/audio_manifest.json";
    final int manifestVersion;
    final String baseUrl;
    private final Map<String, Item> items = new HashMap<>();

    static final class Item {
        final String category, file;
        final int id, version;
        Item(String category, int id, String file, int version) {
            this.category = category; this.id = id; this.file = file; this.version = version;
        }
        String key() { return category + "_" + id; }
        String url(String base) { return base + "/" + category + "/" + file; }
    }

    private AudioManifest(int version, String base) { manifestVersion = version; baseUrl = base; }

    static boolean validId(String category, int id) {
        return id > 0 && ("durood".equals(category) ? id <= 25 : "salam".equals(category) && id <= 15);
    }

    static boolean safeFile(String name) {
        return name != null && !name.contains("..") && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.m4a");
    }

    Item item(String category, int id) { return items.get(category + "_" + id); }

    static AudioManifest parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int revision = positive(root.opt("manifestVersion"));
        String base = root.optString("baseUrl", "");
        URI uri = new URI(base);
        if (revision == 0 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.normalize().equals(uri)) throw new IllegalArgumentException("Invalid manifest header");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        AudioManifest result = new AudioManifest(revision, base);
        for (String category : new String[]{"durood", "salam"}) {
            JSONArray array = root.optJSONArray(category);
            if (array == null) throw new IllegalArgumentException("Missing category " + category);
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null) { AppLogger.w("AudioManifest", "Malformed entry " + category); continue; }
                int id = positive(entry.opt("id"));
                if (!validId(category, id)) { AppLogger.w("AudioManifest", "Invalid ID " + category + ":" + id); continue; }
                String key = category + "_" + id;
                if (!seen.add(id)) {
                    result.items.remove(key);
                    AppLogger.w("AudioManifest", "Duplicate ID unavailable " + key);
                    continue;
                }
                String file = entry.optString("file", "");
                int version = positive(entry.opt("version"));
                if (!safeFile(file) || version == 0) {
                    AppLogger.w("AudioManifest", "Invalid file/version " + key);
                    continue;
                }
                result.items.put(key, new Item(category, id, file, version));
            }
        }
        return result;
    }

    private static int positive(Object value) {
        if (!(value instanceof Number)) return 0;
        double n = ((Number) value).doubleValue();
        return n > 0 && n <= Integer.MAX_VALUE && n == Math.rint(n) ? (int) n : 0;
    }
}
