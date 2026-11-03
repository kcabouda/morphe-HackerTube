package app.morphe.extension.youtube.bespoke;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.shared.NavigationBar;

/**
 * HackerTube-style feed of videos recommended on Hacker News.
 * <p>
 * Hosted by the same activity as the Morphe settings: YouTubeActivityHook.initialize
 * hands over to {@link #showIfRequested(Activity)} when the intent data is
 * {@link #BESPOKE_FEED_INTENT}. The UI is built in code so no resources are needed.
 * <p>
 * Catalog: bespoke/catalog.json in the patches repo, built from the HackerTube data
 * export (hackertube.org/data, ODC-By 1.0). Downloaded once, cached, refreshed weekly.
 */
@SuppressWarnings("unused")
public final class BespokeFeed {

    public static final String BESPOKE_FEED_INTENT = "bespoke_feed_intent";

    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/kcabouda/morphe-HackerTube/main/bespoke/catalog.json";
    private static final long CATALOG_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final String ATTRIBUTION =
            "Data: HackerTube (hackertube.org) · Hacker News via open-index/hacker-news · ODC-By 1.0";


    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> THUMBNAILS =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** Parsed catalog, kept for the life of the process. */
    private static volatile Catalog catalog;

    private BespokeFeed() {
    }

    /**
     * Injection point (called from YouTubeActivityHook.initialize).
     *
     * @return true if the activity was opened for the feed and is now showing it.
     */
    public static boolean showIfRequested(Activity activity) {
        try {
            if (!isFeedActivity(activity)) return false;
            new Screen(activity, false).show();
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Bespoke feed failed to open", ex);
            return false;
        }
    }

    public static boolean isFeedActivity(Activity activity) {
        Intent intent = activity.getIntent();
        return intent != null && BESPOKE_FEED_INTENT.equals(intent.getDataString());
    }

    // region Navigation bar

    /**
     * Injection point (navigation tab created).
     * Turns the Shorts tab into a HackerTube tab that opens the feed.
     */
    public static void navigationTabCreated(NavigationBar.NavigationButton button, View tabView) {
        if (button != NavigationBar.NavigationButton.SHORTS) return;
        try {
            TextView label = null;
            try {
                label = Utils.getChildViewByResourceName(tabView, "text");
                label.setText("HackerTube");
            } catch (Exception ex) {
                Logger.printDebug(() -> "Bespoke feed: Shorts tab has no label");
            }
            try {
                ImageView icon = Utils.getChildViewByResourceName(tabView, "image");
                icon.setImageTintList(null);
                icon.setImageDrawable(new HackerNewsIcon(tabView.getContext()));
            } catch (Exception ex) {
                Logger.printDebug(() -> "Bespoke feed: Shorts tab has no icon");
            }
            if (label != null) {
                label.setContentDescription("HackerTube");
            }

            shortsTab = new WeakReference<>(tabView);
        } catch (Exception ex) {
            Logger.printException(() -> "Bespoke feed navigationTabCreated failure", ex);
        }
    }

    /** The Shorts tab, now the HackerTube tab. */
    private static WeakReference<View> shortsTab = new WeakReference<>(null);
    /** A touch started on the HackerTube tab and is being consumed. */
    private static boolean trackingTabTouch;

    /**
     * Injection point (PivotBar.dispatchTouchEvent, before anything else).
     * Taps on the HackerTube tab open the feed and never reach YouTube,
     * so Shorts is not opened.
     *
     * @return true if the event is consumed.
     */
    public static boolean navigationBarTouched(MotionEvent event) {
        try {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                trackingTabTouch = isOnShortsTab(event);
                if (trackingTabTouch) setTabPressed(true);
                return trackingTabTouch;
            }
            if (!trackingTabTouch) return false;

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                trackingTabTouch = false;
                setTabPressed(false);
                if (action == MotionEvent.ACTION_UP && isOnShortsTab(event)) {
                    View tab = shortsTab.get();
                    if (tab != null) openFeed(tab.getContext());
                }
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Bespoke feed navigationBarTouched failure", ex);
            trackingTabTouch = false;
            return false;
        }
    }

    private static boolean isOnShortsTab(MotionEvent event) {
        View tab = shortsTab.get();
        if (tab == null || !tab.isShown()) return false;
        int[] location = new int[2];
        tab.getLocationOnScreen(location);
        final float x = event.getRawX(), y = event.getRawY();
        return x >= location[0] && x < location[0] + tab.getWidth()
                && y >= location[1] && y < location[1] + tab.getHeight();
    }

    private static void setTabPressed(boolean pressed) {
        View tab = shortsTab.get();
        if (tab != null) tab.setPressed(pressed);
    }

    /** Browse fragments that show the feed instead of their page. */
    private static final java.util.Map<Object, Boolean> feedFragments =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static WeakReference<Screen> currentEmbedded = new WeakReference<>(null);
    /** The feed was requested, and the next History page opened shows it. */
    private static long feedRequestedUntil;

    /**
     * Injection point (browse fragment onCreateView, after the page view is created).
     *
     * @return the feed view if this fragment was opened by the HackerTube tab, otherwise the page view.
     */
    public static View wrapBrowseView(Object fragment, View view, String browseId) {
        try {
            boolean requested = "FEhistory".equals(browseId)
                    && android.os.SystemClock.uptimeMillis() < feedRequestedUntil;
            if (!requested && !feedFragments.containsKey(fragment)) return view;
            feedRequestedUntil = 0;
            feedFragments.put(fragment, Boolean.TRUE);

            Activity activity = findActivity(view.getContext());
            if (activity == null) return view;
            Screen screen = new Screen(activity, true);
            View feed = screen.build();
            currentEmbedded = new WeakReference<>(screen);
            return feed;
        } catch (Exception ex) {
            Logger.printException(() -> "Bespoke feed wrapBrowseView failure", ex);
            return view;
        }
    }

    private static Activity findActivity(Context context) {
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((android.content.ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    /**
     * Opens the feed as a page of the main screen, so the miniplayer stays on top of it.
     * The History page is used as the host page (its content is replaced by the feed).
     */
    public static void openFeed(Context context) {
        try {
            // Tapping the tab again while the feed is shown scrolls it to the top.
            Screen shown = currentEmbedded.get();
            if (shown != null && shown.list != null && shown.list.isShown()) {
                shown.list.smoothScrollToPosition(0);
                return;
            }
            feedRequestedUntil = android.os.SystemClock.uptimeMillis() + 15_000;
            // YouTube deduplicates repeated identical intents, a unique fragment avoids that.
            Uri destination = Uri.parse("https://www.youtube.com/feed/history").buildUpon()
                    .fragment("hackertube-" + java.util.UUID.randomUUID())
                    .build();
            context.startActivity(new Intent(Intent.ACTION_VIEW, destination)
                    .setPackage(context.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            feedRequestedUntil = 0;
            Logger.printException(() -> "Bespoke feed could not be opened", ex);
            openFeedScreen(context);
        }
    }

    /**
     * Opens the feed as a separate screen (used from the settings, and as a fallback).
     */
    public static void openFeedScreen(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(BESPOKE_FEED_INTENT));
            intent.setClassName(context.getPackageName(),
                    "com.google.android.gms.common.api.GoogleApiActivity");
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Bespoke feed could not be opened", ex);
        }
    }

    /**
     * Hacker News style icon: orange rounded square with a white Y.
     */
    private static final class HackerNewsIcon extends Drawable {
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private final RectF rect = new RectF();

        HackerNewsIcon(Context context) {
            size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                    context.getResources().getDisplayMetrics());
            box.setColor(Color.parseColor("#FF6600"));
            letter.setColor(Color.WHITE);
            letter.setTypeface(Typeface.DEFAULT_BOLD);
            letter.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas canvas) {
            float w = getBounds().width(), h = getBounds().height();
            float side = Math.min(w, h) * 0.86f;
            float left = getBounds().left + (w - side) / 2f;
            float top = getBounds().top + (h - side) / 2f;
            rect.set(left, top, left + side, top + side);
            canvas.drawRoundRect(rect, side * 0.18f, side * 0.18f, box);
            letter.setTextSize(side * 0.78f);
            float baseline = rect.centerY() - (letter.descent() + letter.ascent()) / 2f;
            canvas.drawText("Y", rect.centerX(), baseline, letter);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { box.setAlpha(alpha); letter.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // endregion

    // region Data

    private static final int MIN_CATALOG_VERSION = 3;

    private static final class Video {
        final String id, title, channel, area, category, why, type, level;
        final String[] categories;
        final String[] tags;
        final double score;
        final int recommenders, strongRecs, firstYear;
        final String searchText;
        int rank;

        Video(JSONObject o) {
            id = o.optString("v");
            title = o.optString("t");
            channel = o.optString("c");
            area = o.optString("a");
            category = o.optString("k");
            why = o.optString("w");
            type = o.optString("ty");
            level = o.optString("lv");
            score = o.optDouble("s", 0);
            recommenders = o.optInt("r", 0);
            strongRecs = o.optInt("sr", 0);
            firstYear = o.optInt("y", 0);
            JSONArray cs = o.optJSONArray("cs");
            categories = new String[cs == null ? 0 : cs.length()];
            for (int i = 0; i < categories.length; i++) categories[i] = cs.optString(i);
            JSONArray tg = o.optJSONArray("tg");
            tags = new String[tg == null ? 0 : tg.length()];
            for (int i = 0; i < tags.length; i++) tags[i] = tg.optString(i);
            searchText = (title + " " + channel + " " + category + " " + type + " "
                    + TextUtils.join(" ", tags)).toLowerCase(Locale.ROOT);
        }

        /** Features used by the For you ranking. */
        String[] features() {
            List<String> f = new ArrayList<>();
            for (String c : categories) f.add("c:" + c);
            for (String t : tags) f.add("t:" + t);
            if (!channel.isEmpty()) f.add("ch:" + channel.toLowerCase(Locale.ROOT));
            if (!type.isEmpty()) f.add("ty:" + type);
            if (!area.isEmpty()) f.add("a:" + area);
            return f.toArray(new String[0]);
        }

        boolean inCategory(String categoryId) {
            for (String c : categories) if (c.equals(categoryId)) return true;
            return false;
        }
    }

    private static final class Category {
        final String id, name, area;

        Category(String id, String name, String area) {
            this.id = id;
            this.name = name;
            this.area = area;
        }
    }

    private static final class Catalog {
        int version;
        final List<Video> videos = new ArrayList<>();
        final Map<String, Video> byId = new HashMap<>();
        final List<String[]> areas = new ArrayList<>(); // {id, name}
        final List<Category> categories = new ArrayList<>();
        final Map<String, String> categoryArea = new HashMap<>();
        final List<String> types = new ArrayList<>();
        final Map<String, String> areaNames = new HashMap<>();
        ForYou.Model forYou;
        /** Strong picks outside the top: many strong recommendations, lower overall score. */
        final List<Video> deepCuts = new ArrayList<>();

        boolean inArea(Video v, String areaId) {
            if (areaId.equals(v.area)) return true;
            for (String c : v.categories) if (areaId.equals(categoryArea.get(c))) return true;
            return false;
        }
    }

    private static Catalog parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        Catalog c = new Catalog();
        c.version = root.optInt("version", 1);
        JSONArray areas = root.getJSONArray("areas");
        for (int i = 0; i < areas.length(); i++) {
            JSONObject a = areas.getJSONObject(i);
            c.areas.add(new String[]{a.getString("id"), a.getString("name")});
            c.areaNames.put(a.getString("id"), a.getString("name"));
        }
        JSONArray categories = root.optJSONArray("categories");
        if (categories != null) {
            for (int i = 0; i < categories.length(); i++) {
                JSONObject k = categories.getJSONObject(i);
                Category category = new Category(k.getString("id"), k.getString("name"), k.getString("area"));
                c.categories.add(category);
                c.categoryArea.put(category.id, category.area);
            }
        }
        JSONArray videos = root.getJSONArray("videos");
        Map<String, Integer> typeCounts = new HashMap<>();
        for (int i = 0; i < videos.length(); i++) {
            Video v = new Video(videos.getJSONObject(i));
            v.rank = i;
            c.videos.add(v);
            c.byId.put(v.id, v);
            if (!v.type.isEmpty()) {
                Integer count = typeCounts.get(v.type);
                typeCounts.put(v.type, count == null ? 1 : count + 1);
            }
        }
        c.types.addAll(typeCounts.keySet());
        c.types.sort((a, b) -> typeCounts.get(b) - typeCounts.get(a));

        for (Video v : c.videos) {
            if (v.rank >= 400 && v.strongRecs >= 2) c.deepCuts.add(v);
        }
        c.deepCuts.sort((a, b) -> {
            int byStrong = b.strongRecs - a.strongRecs;
            return byStrong != 0 ? byStrong : Double.compare(b.score, a.score);
        });

        List<String[]> features = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        double[] scores = new double[c.videos.size()];
        for (int i = 0; i < c.videos.size(); i++) {
            Video v = c.videos.get(i);
            features.add(v.features());
            ids.add(v.id);
            scores[i] = v.score;
        }
        c.forYou = new ForYou.Model(features, ids, scores);
        return c;
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode() + " for " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Loads the catalog from the cache, downloading it if missing or too old a format.
     * A stale cache is used right away and refreshed for the next opening.
     * Must be called off the main thread.
     */
    private static Catalog loadCatalog(Context context) throws Exception {
        if (catalog != null) return catalog;

        File cache = new File(context.getFilesDir(), "bespoke_catalog.json");
        if (cache.exists()) {
            try {
                Catalog cached = parse(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
                if (cached.version >= MIN_CATALOG_VERSION) {
                    if (System.currentTimeMillis() - cache.lastModified() > CATALOG_MAX_AGE_MILLIS) {
                        Utils.runOnBackgroundThread(() -> refreshCache(cache));
                    }
                    catalog = cached;
                    return cached;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Bespoke feed cache is unreadable, downloading again", ex);
            }
        }

        byte[] data = download(CATALOG_URL);
        Catalog fresh = parse(new String(data, StandardCharsets.UTF_8));
        writeCache(cache, data);
        catalog = fresh;
        return fresh;
    }

    private static void refreshCache(File cache) {
        try {
            byte[] data = download(CATALOG_URL);
            parse(new String(data, StandardCharsets.UTF_8)); // Validate before replacing.
            writeCache(cache, data);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Bespoke feed catalog refresh failed", ex);
        }
    }

    private static void writeCache(File cache, byte[] data) throws Exception {
        File tmp = new File(cache.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(cache)) throw new IllegalStateException("Could not write " + cache);
    }

    // endregion

    // region UI

    private enum Mode {FOR_YOU, EXPLORE, LIKED, SAVED, HISTORY}

    private enum Sort {TOP, SHUFFLE, DEEP_CUTS, NEWEST_ON_HN}

    private static String label(String raw) {
        if (raw.isEmpty()) return raw;
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static final class Screen {
        final Activity activity;
        final boolean embedded;
        final FeedMemory memory;
        final int fg, bg, fgSecondary, chipBg, accent;

        // State.
        Mode mode;
        /** For you picks, computed when For you is opened. */
        List<Video> forYouVideos;
        final Map<String, String> forYouReasons = new HashMap<>();
        final long forYouSeed = System.currentTimeMillis();
        Sort sort = Sort.TOP;
        String areaId = null;
        String categoryId = null;
        final java.util.Set<String> types = new java.util.HashSet<>();
        final java.util.Set<String> levels = new java.util.HashSet<>();
        boolean hideWatched;
        String query = "";
        List<Video> shuffled;

        // Views.
        final List<Video> shown = new ArrayList<>();
        LinearLayout modeRow, sortRow, areaRow, categoryRow;
        View sortScroll, areaScroll, categoryScroll;
        TextView empty;
        View topBlock;
        ListView list;
        FeedAdapter adapter;

        Screen(Activity activity, boolean embedded) {
            this.activity = activity;
            this.embedded = embedded;
            memory = FeedMemory.get(activity);
            fg = ThemeUtils.getAppForegroundColor();
            bg = ThemeUtils.getAppBackgroundColor();
            fgSecondary = (fg & 0x00FFFFFF) | 0x99000000;
            chipBg = (fg & 0x00FFFFFF) | 0x1A000000;
            accent = Color.parseColor("#FF6600"); // Hacker News orange.
            boolean hasTaste = memory.size(FeedMemory.LIKED) + memory.size(FeedMemory.SAVED)
                    + memory.size(FeedMemory.WATCHED) > 0;
            mode = hasTaste ? Mode.FOR_YOU : Mode.EXPLORE;
        }

        int dp(float value) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    activity.getResources().getDisplayMetrics());
        }

        TextView text(float sp, int color, boolean bold) {
            TextView t = new TextView(activity);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        void show() {
            try {
                String theme = Utils.isDarkModeEnabled()
                        ? "Theme.YouTube.Settings.Dark"
                        : "Theme.YouTube.Settings";
                activity.setTheme(ResourceUtils.getIdentifierOrThrow(ResourceType.STYLE, theme));
            } catch (Exception ex) {
                Logger.printInfo(() -> "Bespoke feed could not set theme", ex);
            }
            activity.setContentView(build());
        }

        LinearLayout chipRow(LinearLayout parent, View[] scrollOut) {
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(8), dp(3), dp(8), dp(3));
            scroll.addView(row);
            parent.addView(scroll);
            scrollOut[0] = scroll;
            return row;
        }

        View build() {
            // The list fills the page, and the header block floats over its top.
            // The header slides away while scrolling down and comes back when scrolling up.
            android.widget.FrameLayout root = new android.widget.FrameLayout(activity);
            root.setBackgroundColor(bg);
            if (!embedded) root.setFitsSystemWindows(true);
            root.setClickable(true); // Do not let touches fall through to a page below.

            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.VERTICAL);
            top.setBackgroundColor(bg);
            top.setClickable(true);
            topBlock = top;

            // Header.
            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(4), dp(8), dp(16), dp(4));

            TextView back = text(22, fg, false);
            back.setText("←");
            back.setPadding(dp(12), dp(4), dp(12), dp(4));
            back.setOnClickListener(v -> {
                if (embedded) activity.onBackPressed();
                else activity.finish();
            });
            header.addView(back);

            LinearLayout titles = new LinearLayout(activity);
            titles.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(20, fg, true);
            title.setText("HackerTube");
            TextView subtitle = text(12, fgSecondary, false);
            subtitle.setText("Videos Hacker News keeps recommending");
            titles.addView(title);
            titles.addView(subtitle);
            header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView more = text(22, fg, true);
            more.setText("⋮");
            more.setPadding(dp(16), dp(4), dp(4), dp(4));
            more.setContentDescription("Feed memory");
            more.setOnClickListener(this::showMemoryMenu);
            header.addView(more);
            top.addView(header);

            // Search.
            EditText search = new EditText(activity);
            search.setHint("Search titles, channels, topics");
            search.setHintTextColor(fgSecondary);
            search.setTextColor(fg);
            search.setSingleLine(true);
            search.setInputType(InputType.TYPE_CLASS_TEXT);
            search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            GradientDrawable searchBg = new GradientDrawable();
            searchBg.setCornerRadius(dp(20));
            searchBg.setColor((fg & 0x00FFFFFF) | 0x14000000);
            search.setBackground(searchBg);
            search.setPadding(dp(16), dp(10), dp(16), dp(10));
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            searchParams.setMargins(dp(12), dp(4), dp(12), dp(4));
            top.addView(search, searchParams);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    query = s.toString().trim().toLowerCase(Locale.ROOT);
                    applyFilter();
                }
            });

            View[] scroll = new View[1];
            modeRow = chipRow(top, scroll);
            sortRow = chipRow(top, scroll);
            sortScroll = scroll[0];
            areaRow = chipRow(top, scroll);
            areaScroll = scroll[0];
            categoryRow = chipRow(top, scroll);
            categoryScroll = scroll[0];
            categoryScroll.setVisibility(View.GONE);
            View spacer = new View(activity);
            top.addView(spacer, new LinearLayout.LayoutParams(1, dp(4)));

            list = new ListView(activity);
            list.setDivider(null);
            list.setClipToPadding(false);
            empty = text(14, fgSecondary, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(48), dp(24), dp(48));
            empty.setText("Loading the HackerTube catalog…");
            list.addHeaderView(empty, null, false);
            TextView footer = text(11, fgSecondary, false);
            footer.setText(ATTRIBUTION);
            footer.setGravity(Gravity.CENTER);
            footer.setPadding(dp(16), dp(16), dp(16), dp(32));
            list.addFooterView(footer, null, false);
            adapter = new FeedAdapter();
            list.setAdapter(adapter);
            root.addView(list, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(top, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP));

            // The list starts below the header block.
            top.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int height = b - t;
                if (list.getPaddingTop() != height) {
                    list.setPadding(0, height, 0, list.getPaddingBottom());
                    if (v.getTranslationY() < -height) v.setTranslationY(-height);
                }
            });
            collapseHeaderOnScroll();

            if (embedded) keepAboveNavigationBar(root);

            Utils.runOnBackgroundThread(() -> {
                try {
                    Catalog c = loadCatalog(activity);
                    Utils.runOnMainThread(() -> onCatalog(c));
                } catch (Exception ex) {
                    Logger.printException(() -> "Bespoke feed could not load the catalog", ex);
                    Utils.runOnMainThread(() -> empty.setText(
                            "Could not load the catalog. Check your connection and open the feed again."));
                }
            });
            return root;
        }

        /**
         * The browse page extends behind the navigation bar.
         * Pad the list so its end is not hidden below the bar.
         */
        void keepAboveNavigationBar(View root) {
            list.setClipToPadding(false);
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                try {
                    int[] position = new int[2];
                    v.getLocationOnScreen(position);
                    int bottom = position[1] + v.getHeight();
                    int availableBottom = bottom;
                    int id = ResourceUtils.getIdIdentifier("bottom_bar_container");
                    View navigation = id == 0 ? null : activity.findViewById(id);
                    if (navigation != null && navigation.isShown()) {
                        navigation.getLocationOnScreen(position);
                        availableBottom = Math.min(availableBottom, position[1]);
                    }
                    int covered = Math.max(0, bottom - availableBottom);
                    if (list.getPaddingBottom() != covered) {
                        list.setPadding(0, list.getPaddingTop(), 0, covered);
                    }
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Bespoke feed layout update failed");
                }
            });
        }

        // region Export, import, reset

        void showMemoryMenu(View anchor) {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, anchor);
            menu.getMenu().add(0, 1, 0, "Export feed memory");
            menu.getMenu().add(0, 2, 1, "Import feed memory");
            menu.getMenu().add(0, 3, 2, "Reset feed memory");
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: exportMemory(); break;
                    case 2: importMemory(); break;
                    case 3: resetMemory(); break;
                    default:
                }
                return true;
            });
            menu.show();
        }

        void exportMemory() {
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
            FilePicker.create(activity, "application/json", "bespoke-feed-" + date + ".json", uri -> {
                try (java.io.OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("No output stream");
                    out.write(memory.toJson().getBytes(StandardCharsets.UTF_8));
                    Utils.showToastShort("Feed memory exported");
                } catch (Exception ex) {
                    Logger.printException(() -> "Bespoke feed export failed", ex);
                    Utils.showToastShort("Export failed");
                }
            });
        }

        void importMemory() {
            FilePicker.open(activity, uri -> {
                final String json;
                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("No input stream");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (out.size() > 20 * 1024 * 1024) throw new IllegalStateException("File too large");
                    }
                    json = out.toString("UTF-8");
                    new JSONObject(json); // Validate before asking.
                } catch (Exception ex) {
                    Logger.printException(() -> "Bespoke feed import could not read the file", ex);
                    Utils.showToastShort("This file can't be read");
                    return;
                }
                new android.app.AlertDialog.Builder(activity)
                        .setTitle("Import feed memory")
                        .setMessage("Merge keeps what's on this phone and adds the file's likes, saves "
                                + "and history. Replace swaps everything for the file's content.")
                        .setPositiveButton("Merge", (d, w) -> doImport(json, false))
                        .setNeutralButton("Replace", (d, w) -> doImport(json, true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }

        void doImport(String json, boolean replace) {
            try {
                int count = memory.importJson(json, replace);
                Utils.showToastShort("Imported " + count + " entries");
                memoryChanged();
            } catch (Exception ex) {
                Logger.printException(() -> "Bespoke feed import failed", ex);
                Utils.showToastShort("This is not a feed memory export");
            }
        }

        void resetMemory() {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Reset feed memory?")
                    .setMessage("This deletes your likes, saves, history and not interested videos "
                            + "on this phone. Export first if you want a backup.")
                    .setPositiveButton("Reset", (d, w) -> {
                        memory.reset();
                        Utils.showToastShort("Feed memory reset");
                        memoryChanged();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        void memoryChanged() {
            forYouVideos = null;
            refresh(true);
        }

        // endregion

        /** Moves the header block with the scroll, then snaps it fully in or out. */
        void collapseHeaderOnScroll() {
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                int lastFirst = -1;
                int lastTop;

                @Override
                public void onScrollStateChanged(AbsListView view, int state) {
                    if (state != SCROLL_STATE_IDLE) return;
                    int height = topBlock.getHeight();
                    float y = topBlock.getTranslationY();
                    boolean atTop = view.getFirstVisiblePosition() == 0;
                    float target = (atTop || y > -height / 2f) ? 0 : -height;
                    if (y != target) topBlock.animate().translationY(target).setDuration(150).start();
                }

                @Override
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    View child = view.getChildAt(0);
                    if (child == null) return;
                    int childTop = child.getTop();
                    int dy;
                    if (lastFirst < 0) {
                        dy = 0;
                    } else if (first == lastFirst) {
                        dy = lastTop - childTop;
                    } else {
                        dy = first > lastFirst ? dp(24) : -dp(24);
                    }
                    lastFirst = first;
                    lastTop = childTop;
                    if (dy == 0) return;

                    int height = topBlock.getHeight();
                    float y = topBlock.getTranslationY() - dy;
                    if (first == 0 && childTop >= view.getPaddingTop() - dp(4)) y = 0;
                    y = Math.max(-height, Math.min(0, y));
                    topBlock.animate().cancel();
                    topBlock.setTranslationY(y);
                }
            });
        }

        void showHeader() {
            if (topBlock != null) {
                topBlock.animate().cancel();
                topBlock.setTranslationY(0);
            }
        }

        void onCatalog(Catalog c) {
            if (activity.isFinishing() || modeRow.getChildCount() > 0) return;
            rebuildChips();
            applyFilter();
        }

        // region Chips

        TextView chip(LinearLayout row, String label, boolean selected, View.OnClickListener onClick) {
            TextView chip = text(14, selected ? bg : fg, false);
            chip.setText(label);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(8));
            d.setColor(selected ? fg : chipBg);
            chip.setBackground(d);
            chip.setOnClickListener(onClick);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(4), 0, dp(4), 0);
            row.addView(chip, params);
            return chip;
        }

        void rebuildChips() {
            Catalog c = catalog;
            if (c == null) return;

            modeRow.removeAllViews();
            chip(modeRow, "For you", mode == Mode.FOR_YOU, v -> setMode(Mode.FOR_YOU));
            chip(modeRow, "Explore", mode == Mode.EXPLORE, v -> setMode(Mode.EXPLORE));
            chip(modeRow, "♥ Liked " + memory.size(FeedMemory.LIKED), mode == Mode.LIKED, v -> setMode(Mode.LIKED));
            chip(modeRow, "★ Saved " + memory.size(FeedMemory.SAVED), mode == Mode.SAVED, v -> setMode(Mode.SAVED));
            chip(modeRow, "History", mode == Mode.HISTORY, v -> setMode(Mode.HISTORY));

            boolean explore = mode == Mode.EXPLORE;
            sortScroll.setVisibility(explore ? View.VISIBLE : View.GONE);
            areaScroll.setVisibility(explore ? View.VISIBLE : View.GONE);
            categoryScroll.setVisibility(explore && areaId != null ? View.VISIBLE : View.GONE);
            if (!explore) return;

            sortRow.removeAllViews();
            chip(sortRow, "Top", sort == Sort.TOP, v -> setSort(Sort.TOP));
            chip(sortRow, "Shuffle", sort == Sort.SHUFFLE, v -> {
                shuffled = null; // A new shuffle on every tap.
                setSort(Sort.SHUFFLE);
            });
            chip(sortRow, "Deep cuts", sort == Sort.DEEP_CUTS, v -> setSort(Sort.DEEP_CUTS));
            chip(sortRow, "Newest on HN", sort == Sort.NEWEST_ON_HN, v -> setSort(Sort.NEWEST_ON_HN));
            chip(sortRow, types.isEmpty() ? "Type ▾" : "Type (" + types.size() + ") ▾",
                    !types.isEmpty(), v -> pickMany("Type", c.types, types));
            chip(sortRow, levels.isEmpty() ? "Level ▾" : "Level (" + levels.size() + ") ▾",
                    !levels.isEmpty(), v -> pickMany("Level",
                            java.util.Arrays.asList("intro", "general", "intermediate", "advanced"), levels));
            chip(sortRow, "Hide watched", hideWatched, v -> {
                hideWatched = !hideWatched;
                refresh(true);
            });

            areaRow.removeAllViews();
            chip(areaRow, "All topics", areaId == null, v -> setArea(null));
            for (String[] area : c.areas) {
                chip(areaRow, area[1], area[0].equals(areaId), v -> setArea(area[0]));
            }

            categoryRow.removeAllViews();
            if (areaId != null) {
                chip(categoryRow, "All", categoryId == null, v -> setCategory(null));
                for (Category category : c.categories) {
                    if (!category.area.equals(areaId)) continue;
                    chip(categoryRow, category.name, category.id.equals(categoryId), v -> setCategory(category.id));
                }
            }
        }

        void pickMany(String title, List<String> options, java.util.Set<String> selected) {
            String[] labels = new String[options.size()];
            boolean[] checked = new boolean[options.size()];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = label(options.get(i));
                checked[i] = selected.contains(options.get(i));
            }
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        selected.clear();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(options.get(i));
                        refresh(true);
                    })
                    .setNeutralButton("Clear", (dialog, which) -> {
                        selected.clear();
                        refresh(true);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        void setMode(Mode mode) {
            if (mode == Mode.FOR_YOU) forYouVideos = null;
            this.mode = mode;
            refresh(true);
        }

        void setSort(Sort sort) {
            this.sort = sort;
            refresh(true);
        }

        void setArea(String areaId) {
            this.areaId = areaId;
            this.categoryId = null;
            refresh(true);
        }

        void setCategory(String categoryId) {
            this.categoryId = categoryId;
            refresh(true);
        }

        void refresh(boolean scrollToTop) {
            rebuildChips();
            applyFilter();
            if (scrollToTop) {
                showHeader();
                list.setSelection(0);
            }
        }

        // endregion

        List<Video> source(Catalog c) {
            switch (mode) {
                case FOR_YOU: return forYou(c);
                case LIKED: return fromIds(c, memory.ids(FeedMemory.LIKED));
                case SAVED: return fromIds(c, memory.ids(FeedMemory.SAVED));
                case HISTORY: return fromIds(c, memory.ids(FeedMemory.WATCHED));
                default:
            }
            switch (sort) {
                case SHUFFLE:
                    if (shuffled == null) {
                        shuffled = new ArrayList<>(c.videos);
                        Collections.shuffle(shuffled);
                    }
                    return shuffled;
                case DEEP_CUTS:
                    return c.deepCuts;
                case NEWEST_ON_HN: {
                    List<Video> newest = new ArrayList<>(c.videos);
                    newest.sort((a, b) -> {
                        int byYear = b.firstYear - a.firstYear;
                        return byYear != 0 ? byYear : Double.compare(b.score, a.score);
                    });
                    return newest;
                }
                default:
                    return c.videos;
            }
        }

        List<Video> forYou(Catalog c) {
            // Computed when For you is opened, so liking a pick does not reshuffle the list.
            if (forYouVideos != null) return forYouVideos;
            java.util.Set<String> excluded = new java.util.HashSet<>();
            excluded.addAll(memory.ids(FeedMemory.WATCHED));
            excluded.addAll(memory.ids(FeedMemory.LIKED));
            excluded.addAll(memory.ids(FeedMemory.SAVED));
            excluded.addAll(memory.ids(FeedMemory.HIDDEN));
            List<ForYou.Pick> picks = ForYou.rank(c.forYou, ForYou.signals(memory), excluded,
                    new ForYou.Titles() {
                        @Override public String title(int index) { return c.videos.get(index).title; }
                        @Override public String area(int index) { return c.videos.get(index).area; }
                        @Override public String areaName(String areaId) {
                            String name = c.areaNames.get(areaId);
                            return name == null ? "other topics" : name;
                        }
                    }, 150, forYouSeed);
            List<Video> videos = new ArrayList<>();
            forYouReasons.clear();
            for (ForYou.Pick pick : picks) {
                Video v = c.videos.get(pick.index);
                videos.add(v);
                forYouReasons.put(v.id, pick.reason);
            }
            forYouVideos = videos;
            return videos;
        }

        List<Video> fromIds(Catalog c, List<String> ids) {
            List<Video> videos = new ArrayList<>();
            for (String id : ids) {
                Video v = c.byId.get(id);
                if (v != null) videos.add(v);
            }
            return videos;
        }

        void applyFilter() {
            Catalog c = catalog;
            if (c == null) return;
            final boolean explore = mode == Mode.EXPLORE;
            shown.clear();
            for (Video v : source(c)) {
                if (!query.isEmpty() && !v.searchText.contains(query)) continue;
                if (!explore) {
                    if (mode == Mode.FOR_YOU && memory.contains(FeedMemory.HIDDEN, v.id)) continue;
                    shown.add(v);
                    continue;
                }
                if (memory.contains(FeedMemory.HIDDEN, v.id)) continue;
                if (hideWatched && memory.contains(FeedMemory.WATCHED, v.id)) continue;
                if (areaId != null && !c.inArea(v, areaId)) continue;
                if (categoryId != null && !v.inCategory(categoryId)) continue;
                if (!types.isEmpty() && !types.contains(v.type)) continue;
                if (!levels.isEmpty() && !levels.contains(v.level)) continue;
                shown.add(v);
            }

            String emptyText = null;
            if (shown.isEmpty()) {
                switch (mode) {
                    case FOR_YOU: emptyText = "Like, save or watch a few videos from Explore,"
                            + " and your picks will show up here."; break;
                    case LIKED: emptyText = "Nothing liked yet. Tap ♡ Like on a video."; break;
                    case SAVED: emptyText = "Nothing saved yet. Tap ☆ Save on a video to watch it later."; break;
                    case HISTORY: emptyText = "Videos you open from HackerTube show up here."; break;
                    default: emptyText = "No videos match. Try fewer filters.";
                }
            } else if (explore) {
                emptyText = shown.size() + (shown.size() == 1 ? " video" : " videos");
            }
            empty.setText(emptyText);
            empty.setVisibility(emptyText == null ? View.GONE : View.VISIBLE);
            empty.setPadding(dp(24), dp(shown.isEmpty() ? 32 : 4), dp(24), dp(shown.isEmpty() ? 8 : 4));
            adapter.notifyDataSetChanged();
        }

        void open(Video video) {
            try {
                memory.add(FeedMemory.WATCHED, video.id);
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/watch?v=" + video.id));
                intent.setPackage(activity.getPackageName());
                activity.startActivity(intent);
                // Show the watched state when coming back.
                Utils.runOnMainThread(() -> {
                    rebuildChips();
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception ex) {
                Logger.printException(() -> "Bespoke feed could not open " + video.id, ex);
            }
        }

        void toggle(String listName, Video video) {
            boolean added = memory.toggle(listName, video.id);
            if (FeedMemory.HIDDEN.equals(listName) && added) {
                Utils.showToastShort("Hidden. You won't see this video in Explore.");
            }
            rebuildChips();
            applyFilter();
        }

        final class FeedAdapter extends BaseAdapter {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int position) { return shown.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Row row = convertView == null ? new Row() : (Row) convertView.getTag();
                row.bind(shown.get(position));
                return row.view;
            }
        }

        final class Row {
            final LinearLayout view;
            final ImageView thumbnail;
            final TextView reason, title, meta, why, like, save, hide;
            Video video;

            Row() {
                view = new LinearLayout(activity);
                view.setOrientation(LinearLayout.VERTICAL);
                view.setPadding(0, 0, 0, dp(16));
                view.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                // Cards handle their own taps, so the action buttons stay tappable.
                view.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

                // 16:9 frame from the middle of the video, not the uploader's thumbnail.
                thumbnail = new ImageView(activity) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = View.MeasureSpec.getSize(widthSpec);
                        setMeasuredDimension(width, width * 9 / 16);
                    }
                };
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackgroundColor((fg & 0x00FFFFFF) | 0x14000000);
                view.addView(thumbnail, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout texts = new LinearLayout(activity);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(12), dp(8), dp(12), 0);

                reason = text(12, accent, true);
                reason.setMaxLines(1);
                reason.setEllipsize(TextUtils.TruncateAt.END);
                reason.setPadding(0, 0, 0, dp(2));

                title = text(16, fg, true);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                meta = text(12, fgSecondary, false);
                meta.setMaxLines(2);
                meta.setEllipsize(TextUtils.TruncateAt.END);
                why = text(13, fgSecondary, false);
                why.setMaxLines(2);
                why.setEllipsize(TextUtils.TruncateAt.END);
                why.setPadding(0, dp(4), 0, 0);

                texts.addView(reason);
                texts.addView(title);
                texts.addView(meta);
                texts.addView(why);

                LinearLayout actions = new LinearLayout(activity);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setPadding(0, dp(6), 0, 0);
                like = action(actions);
                save = action(actions);
                hide = action(actions);
                hide.setText("Not interested");
                texts.addView(actions);
                view.addView(texts);

                View.OnClickListener openListener = v -> {
                    if (video != null) open(video);
                };
                thumbnail.setOnClickListener(openListener);
                title.setOnClickListener(openListener);
                why.setOnClickListener(openListener);
                meta.setOnClickListener(openListener);
                like.setOnClickListener(v -> {
                    if (video != null) toggle(FeedMemory.LIKED, video);
                });
                save.setOnClickListener(v -> {
                    if (video != null) toggle(FeedMemory.SAVED, video);
                });
                hide.setOnClickListener(v -> {
                    if (video != null) toggle(FeedMemory.HIDDEN, video);
                });
                view.setTag(this);
            }

            TextView action(LinearLayout parent) {
                TextView t = text(13, fg, false);
                t.setPadding(dp(12), dp(6), dp(12), dp(6));
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(dp(16));
                d.setColor(chipBg);
                t.setBackground(d);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, 0, dp(8), 0);
                parent.addView(t, params);
                return t;
            }

            void bind(Video v) {
                video = v;
                String pickReason = mode == Mode.FOR_YOU ? forYouReasons.get(v.id) : null;
                reason.setText(pickReason);
                reason.setVisibility(pickReason == null ? View.GONE : View.VISIBLE);
                title.setText(v.title);
                StringBuilder m = new StringBuilder();
                if (!v.channel.isEmpty()) m.append(v.channel).append(" · ");
                m.append(v.recommenders).append(v.recommenders == 1 ? " HN recommender" : " HN recommenders");
                if (!v.category.isEmpty()) m.append(" · ").append(v.category);
                if (!v.type.isEmpty()) m.append(" · ").append(label(v.type));
                boolean watched = memory.contains(FeedMemory.WATCHED, v.id);
                if (watched && mode != Mode.HISTORY) m.append(" · Watched");
                meta.setText(m);
                why.setText(v.why);
                why.setVisibility(v.why.isEmpty() ? View.GONE : View.VISIBLE);

                boolean liked = memory.contains(FeedMemory.LIKED, v.id);
                like.setText(liked ? "♥ Liked" : "♡ Like");
                like.setTextColor(liked ? accent : fg);
                boolean saved = memory.contains(FeedMemory.SAVED, v.id);
                save.setText(saved ? "★ Saved" : "☆ Save");
                save.setTextColor(saved ? accent : fg);
                boolean hidden = memory.contains(FeedMemory.HIDDEN, v.id);
                hide.setText(hidden ? "Show again" : "Not interested");
                hide.setVisibility(mode == Mode.EXPLORE || mode == Mode.FOR_YOU || hidden
                        ? View.VISIBLE : View.GONE);

                thumbnail.setAlpha(watched && mode == Mode.EXPLORE ? 0.55f : 1f);
                loadThumbnail(thumbnail, v.id);
            }
        }
    }

    private static void loadThumbnail(ImageView view, String videoId) {
        view.setTag(videoId);
        Bitmap cached = THUMBNAILS.get(videoId);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        IMAGE_EXECUTOR.execute(() -> {
            try {
                if (!videoId.equals(view.getTag())) return; // Row was recycled.
                byte[] data = download("https://i.ytimg.com/vi/" + videoId + "/hq2.jpg");
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return;
                THUMBNAILS.put(videoId, bitmap);
                Utils.runOnMainThread(() -> {
                    if (videoId.equals(view.getTag())) view.setImageBitmap(bitmap);
                });
            } catch (Exception ex) {
                Logger.printDebug(() -> "Bespoke feed thumbnail failed: " + videoId);
            }
        });
    }

    // endregion
}
