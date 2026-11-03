package app.morphe.extension.youtube.bespoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * On-device "For you" ranking. Content based and explainable:
 * <ul>
 *   <li>Each video is described by features: its topics, tags, channel, type and area,
 *       weighted by how rare they are in the catalog (IDF).</li>
 *   <li>Your taste profile is the sum of the features of what you liked (+3), saved (+2)
 *       and watched (+1), minus what you marked not interested (-3). Recent signals count more.</li>
 *   <li>Score = 80% similarity to your profile + 20% Hacker News endorsement.</li>
 *   <li>The list is then diversified (fewer repeats of a channel or topic) and every 7th
 *       slot is a well-recommended video from outside your usual topics.</li>
 * </ul>
 * Nothing leaves the device.
 */
final class ForYou {

    /** A recommended video and why it was picked. */
    static final class Pick {
        final int index; // Index in the catalog video list.
        final String reason;

        Pick(int index, String reason) {
            this.index = index;
            this.reason = reason;
        }
    }

    /** Catalog side, built once: features of each video and their IDF weights. */
    static final class Model {
        final List<String[]> features = new ArrayList<>();
        final Map<String, Double> idf = new HashMap<>();
        final double[] prior;
        final Map<String, Integer> indexById = new HashMap<>();
        final List<String> ids;

        Model(List<String[]> videoFeatures, List<String> ids, double[] scores) {
            features.addAll(videoFeatures);
            this.ids = new ArrayList<>(ids);
            Map<String, Integer> df = new HashMap<>();
            for (String[] fs : videoFeatures) {
                for (String f : new HashSet<>(java.util.Arrays.asList(fs))) {
                    Integer n = df.get(f);
                    df.put(f, n == null ? 1 : n + 1);
                }
            }
            final double n = Math.max(1, videoFeatures.size());
            for (Map.Entry<String, Integer> e : df.entrySet()) {
                // Features on a single video do not help to find similar ones.
                if (e.getValue() < 2) continue;
                idf.put(e.getKey(), Math.log(n / e.getValue()));
            }
            double maxScore = 0;
            for (double s : scores) maxScore = Math.max(maxScore, s);
            prior = new double[scores.length];
            for (int i = 0; i < scores.length; i++) {
                prior[i] = maxScore <= 0 ? 0 : Math.log1p(scores[i]) / Math.log1p(maxScore);
            }
            for (int i = 0; i < ids.size(); i++) indexById.put(ids.get(i), i);
        }

        double weight(String feature) {
            Double w = idf.get(feature);
            return w == null ? 0 : w;
        }
    }

    /** A signal from the feed memory. */
    static final class Signal {
        final String videoId;
        final double weight;
        final String verb; // "liked", "saved", "watched"

        Signal(String videoId, double weight, String verb) {
            this.videoId = videoId;
            this.weight = weight;
            this.verb = verb;
        }
    }

    interface Titles {
        String title(int index);

        String area(int index);

        String areaName(String areaId);
    }

    static List<Signal> signals(FeedMemory memory) {
        List<Signal> signals = new ArrayList<>();
        add(signals, memory.ids(FeedMemory.LIKED), 3, "liked");
        add(signals, memory.ids(FeedMemory.SAVED), 2, "saved");
        // Watched videos count by how much of them was watched:
        // dropped early = a weak "not for me", most of it = a strong "more like this".
        List<String> watched = memory.ids(FeedMemory.WATCHED);
        int count = Math.min(100, watched.size());
        for (int i = 0; i < count; i++) {
            String id = watched.get(i);
            int percent = memory.progress(id);
            double weight = percent < 0 ? 0.5 : percent < 20 ? -0.5 : percent < 60 ? 0.7 : 1.5;
            double decay = 1.0 / (1.0 + i / 50.0);
            signals.add(new Signal(id, weight * decay, "watched"));
        }
        add(signals, memory.ids(FeedMemory.HIDDEN), -3, null);
        return signals;
    }

    private static void add(List<Signal> out, List<String> idsNewestFirst, double weight, String verb) {
        // The most recent 100 of each list, with a gentle decay: the 50th counts half.
        int count = Math.min(100, idsNewestFirst.size());
        for (int i = 0; i < count; i++) {
            double decay = 1.0 / (1.0 + i / 50.0);
            out.add(new Signal(idsNewestFirst.get(i), weight * decay, verb));
        }
    }

    /**
     * @param excluded video ids not to recommend (watched, liked, saved, hidden).
     * @return picks in display order, or an empty list if there are no positive signals yet.
     */
    static List<Pick> rank(Model model, List<Signal> signals, Set<String> excluded,
                           Titles titles, int limit, long seed) {
        // Profile.
        Map<String, Double> profile = new HashMap<>();
        boolean anyPositive = false;
        List<Integer> positiveSignals = new ArrayList<>();
        List<String> positiveVerbs = new ArrayList<>();
        for (Signal s : signals) {
            Integer index = model.indexById.get(s.videoId);
            if (index == null) continue;
            if (s.weight > 0) {
                anyPositive = true;
                if (positiveSignals.size() < 60) {
                    positiveSignals.add(index);
                    positiveVerbs.add(s.verb);
                }
            }
            for (String f : model.features.get(index)) {
                double w = model.weight(f);
                if (w == 0) continue;
                Double current = profile.get(f);
                profile.put(f, (current == null ? 0 : current) + s.weight * w);
            }
        }
        if (!anyPositive) return Collections.emptyList();

        // Similarity of every candidate.
        final int n = model.features.size();
        double[] similarity = new double[n];
        double maxSimilarity = 0;
        for (int i = 0; i < n; i++) {
            String[] fs = model.features.get(i);
            if (fs.length == 0) continue;
            double dot = 0;
            for (String f : fs) {
                Double p = profile.get(f);
                if (p != null) dot += p * model.weight(f);
            }
            similarity[i] = dot / Math.sqrt(fs.length);
            maxSimilarity = Math.max(maxSimilarity, similarity[i]);
        }

        List<Integer> candidates = new ArrayList<>();
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            if (excluded.contains(model.ids.get(i))) continue;
            double sim = maxSimilarity <= 0 ? 0 : Math.max(0, similarity[i]) / maxSimilarity;
            score[i] = 0.8 * sim + 0.2 * model.prior[i];
            candidates.add(i);
        }
        candidates.sort((a, b) -> Double.compare(score[b], score[a]));
        List<Integer> pool = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), limit * 3)));

        // Areas the profile is mostly about, to pick exploration videos outside of them.
        Map<String, Double> areaWeight = new HashMap<>();
        for (Map.Entry<String, Double> e : profile.entrySet()) {
            if (e.getKey().startsWith("a:") && e.getValue() > 0) {
                areaWeight.put(e.getKey().substring(2), e.getValue());
            }
        }
        List<Integer> explore = new ArrayList<>();
        for (int i : candidates) {
            if (explore.size() >= 200) break;
            if (model.prior[i] < 0.5) continue;
            if (!areaWeight.containsKey(titles.area(i))) explore.add(i);
        }
        Random random = new Random(seed);
        Collections.shuffle(explore, random);

        // Greedy diversified selection.
        List<Pick> picks = new ArrayList<>();
        Set<Integer> chosen = new HashSet<>();
        Map<String, Integer> seenChannel = new HashMap<>();
        Map<String, Integer> seenTopic = new HashMap<>();
        int exploreIndex = 0;
        while (picks.size() < limit && !pool.isEmpty()) {
            if ((picks.size() + 1) % 7 == 0 && exploreIndex < explore.size()) {
                int e = explore.get(exploreIndex++);
                if (chosen.add(e)) {
                    String areaName = titles.areaName(titles.area(e));
                    picks.add(new Pick(e, "Something different · popular on HN in " + areaName));
                    pool.remove(Integer.valueOf(e));
                    continue;
                }
            }
            int best = -1;
            double bestValue = -1e9;
            int scanned = 0;
            for (int i : pool) {
                if (scanned++ > 150) break; // The pool is sorted, the best is near the top.
                double penalty = 0;
                for (String f : model.features.get(i)) {
                    if (f.startsWith("ch:")) {
                        Integer c = seenChannel.get(f);
                        if (c != null) penalty += 0.12 * c;
                    } else if (f.startsWith("c:")) {
                        Integer c = seenTopic.get(f);
                        if (c != null) penalty += 0.03 * c;
                    }
                }
                double value = score[i] - penalty;
                if (value > bestValue) {
                    bestValue = value;
                    best = i;
                }
            }
            if (best < 0) break;
            pool.remove(Integer.valueOf(best));
            chosen.add(best);
            for (String f : model.features.get(best)) {
                if (f.startsWith("ch:")) seenChannel.merge(f, 1, Integer::sum);
                else if (f.startsWith("c:")) seenTopic.merge(f, 1, Integer::sum);
            }
            picks.add(new Pick(best, reason(model, best, positiveSignals, positiveVerbs, titles)));
        }
        return picks;
    }

    /** The signal sharing the most (weighted) features with the video. */
    private static String reason(Model model, int video, List<Integer> signals, List<String> verbs, Titles titles) {
        String[] fs = model.features.get(video);
        Set<String> own = new HashSet<>(java.util.Arrays.asList(fs));
        int best = -1;
        double bestOverlap = 0;
        for (int s = 0; s < signals.size(); s++) {
            double overlap = 0;
            for (String f : model.features.get(signals.get(s))) {
                if (own.contains(f)) overlap += model.weight(f);
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = s;
            }
        }
        if (best < 0) return "Popular on HN";
        return "Because you " + verbs.get(best) + " “" + titles.title(signals.get(best)) + "”";
    }
}
