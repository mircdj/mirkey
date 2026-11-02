/*
 * MirKey — zone di tocco dinamiche.
 *
 * Ogni tasto ha un "centro protetto" che risponde sempre a quel tasto. Il bordo esterno invece
 * viene conteso dai tasti vicini: vince quello che combina meglio la distanza dal dito e la
 * probabilità della lettera successiva, data la parola che si sta scrivendo.
 * Dopo "grazi" la E si allarga, la W si restringe. Dopo una parola completa si allarga lo spazio.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.mirkey;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.latin.WordComposer;

public final class MirZones {
    private static final String TAG = "MirZones";

    // Dispersione del tocco, in frazioni della dimensione di un tasto
    private static final float SIGMA_X = 0.45f;
    private static final float SIGMA_Y = 0.42f;
    // Peso del modello della lingua rispetto alla posizione del dito
    private static final float LM_WEIGHT = 0.8f;
    // Fiducia nel dizionario rispetto a una distribuzione piatta
    private static final float LAMBDA = 0.85f;
    // Frazione del tasto, su ogni lato, che può essere contesa dai vicini
    private static final float CORE_MARGIN = 0.22f;
    // Distanza massima (in tasti) da cui un tasto può "rubare" un tocco
    private static final float REACH = 1.2f;
    // Probabilità fissa per i tasti funzione (maiuscole, cancella, simboli...)
    private static final float OTHER_PRIOR = 0.02f;

    private static final Set<String> ELISIONS = new HashSet<>(Arrays.asList(
            "l", "dell", "nell", "dall", "sull", "all", "coll", "un", "quest", "quell", "c", "d", "m",
            "t", "s", "v", "n", "anch", "com", "dov", "tutt", "bell", "sant", "po"));

    private static Context sContext;
    private static WordComposer sComposer;
    private static volatile Model sModel;
    private static volatile boolean sLoading;
    private static volatile boolean sFailed;

    // cache dell'ultima distribuzione calcolata (solo thread dell'interfaccia)
    private static String sCachedPrefix;
    private static Model sCachedModel;
    private static float[] sCachedDist;

    private MirZones() { }

    public static void init(final Context context) {
        if (sContext == null && context != null) sContext = context.getApplicationContext();
        modelFor(Locale.ITALIAN); // carica il dizionario in anticipo, in background
    }

    public static void attachComposer(final WordComposer composer) {
        sComposer = composer;
    }

    /**
     * Sceglie il tasto per un tocco. {@code base} è il tasto che il layout assegnerebbe
     * normalmente; viene restituito invariato quando il tocco cade nel suo centro o quando
     * non c'è un modello per la lingua corrente.
     */
    public static Key adjust(final Keyboard keyboard, final Key base, final int x, final int y) {
        if (base == null || keyboard == null || keyboard.mId == null) return base;
        try {
            if (!keyboard.mId.getElement().isAlphabet() || keyboard.mId.isPasswordInput()) return base;
            final float ux = keyboard.mMostCommonKeyWidth;
            final float uy = keyboard.mMostCommonKeyHeight;
            if (ux <= 0 || uy <= 0) return base;
            if (inCore(base, x, y, ux, uy)) return base;
            final Model model = modelFor(keyboard.mId.getLocale());
            if (model == null) return base;
            final float[] dist = distribution(model, currentPrefix());
            if (dist == null) return base; // parola fuori dal dizionario: comportamento normale

            Key best = base;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (final Key key : keyboard.getSortedKeys()) {
                if (key.isSpacer() || !key.isEnabled() || key.getWidth() <= 0) continue;
                final float w = key.getWidth(), h = key.getHeight();
                final float seg = Math.max(0f, (w - ux) / 2f);
                float dx = x - (key.getX() + w / 2f);
                if (dx > seg) dx -= seg; else if (dx < -seg) dx += seg; else dx = 0f;
                final float dy = y - (key.getY() + h / 2f);
                final float ax = dx / ux, ay = dy / uy;
                if (ax * ax + ay * ay > REACH * REACH) continue;
                final float prior = priorFor(key.getCode(), dist);
                if (prior <= 0f) continue;
                final float nx = ax / SIGMA_X, ny = ay / SIGMA_Y;
                final double score = -0.5 * (nx * nx + ny * ny) + LM_WEIGHT * Math.log(prior);
                if (score > bestScore) {
                    bestScore = score;
                    best = key;
                }
            }
            return best;
        } catch (final RuntimeException e) {
            Log.w(TAG, "zone dinamiche disattivate per questo tocco", e);
            return base;
        }
    }

    private static boolean inCore(final Key key, final int x, final int y, final float ux, final float uy) {
        final float w = key.getWidth(), h = key.getHeight();
        final float mx = CORE_MARGIN * Math.min(w, ux), my = CORE_MARGIN * Math.min(h, uy);
        return x >= key.getX() + mx && x <= key.getX() + w - mx
                && y >= key.getY() + my && y <= key.getY() + h - my;
    }

    private static String currentPrefix() {
        final WordComposer composer = sComposer;
        if (composer == null || !composer.isComposingWord()) return "";
        return composer.getTypedWord();
    }

    // dist: [0..25] lettere, [26] fine parola, [27] apostrofo (elisione)
    private static float priorFor(final int code, final float[] dist) {
        if (code > 0) {
            final int c = baseLetter(code);
            if (c >= 'a' && c <= 'z') return LAMBDA * dist[c - 'a'] + (1 - LAMBDA) * (0.86f / 26f);
            final float end = dist[26];
            switch (code) {
                case ' ': return LAMBDA * end * 0.84f + (1 - LAMBDA) * 0.08f;
                case ',': return LAMBDA * end * 0.07f + (1 - LAMBDA) * 0.02f;
                case '.': return LAMBDA * end * 0.06f + (1 - LAMBDA) * 0.02f;
                case '?': return LAMBDA * end * 0.03f + (1 - LAMBDA) * 0.01f;
                case '\'': case '\u2019': return LAMBDA * dist[27] + (1 - LAMBDA) * 0.01f;
                default: return OTHER_PRIOR;
            }
        }
        return OTHER_PRIOR;
    }

    private static int baseLetter(final int code) {
        int c = Character.toLowerCase(code);
        if (c >= 'a' && c <= 'z') return c;
        if (c < 0x00C0 || c > 0x017F) return -1;
        final String s = Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFD);
        c = s.isEmpty() ? -1 : s.charAt(0);
        return (c >= 'a' && c <= 'z') ? c : -1;
    }

    private static String normalize(final String word) {
        final String lower = word.toLowerCase(Locale.ROOT);
        final String n = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        for (int i = 0; i < n.length(); i++) {
            final char ch = n.charAt(i);
            if (ch < 'a' || ch > 'z') return null;
        }
        return n;
    }

    private static float[] distribution(final Model model, final String typed) {
        if (model == sCachedModel && typed.equals(sCachedPrefix)) return sCachedDist;
        final String prefix = normalize(typed);
        float[] dist = null;
        if (prefix != null) {
            dist = prefix.isEmpty() ? model.root : model.next(prefix);
            if (dist != null && ELISIONS.contains(prefix)) {
                dist = dist.clone();
                for (int i = 0; i < 27; i++) dist[i] *= 0.7f;
                dist[27] = 0.3f;
            }
        }
        sCachedModel = model;
        sCachedPrefix = typed;
        sCachedDist = dist;
        return dist;
    }

    private static Model modelFor(final Locale locale) {
        if (locale == null || !"it".equals(locale.getLanguage())) return null;
        final Model m = sModel;
        if (m != null) return m;
        if (!sLoading && !sFailed && sContext != null) {
            sLoading = true;
            final Context context = sContext;
            new Thread(() -> {
                try {
                    sModel = Model.load(context, "it");
                } catch (final Exception e) {
                    sFailed = true;
                    Log.e(TAG, "impossibile caricare il dizionario delle zone", e);
                } finally {
                    sLoading = false;
                }
            }, "MirZones-load").start();
        }
        return null;
    }

    /** Elenco ordinato di parole con frequenza: basta una ricerca binaria per ogni prefisso. */
    static final class Model {
        final String[] words;
        final float[] freq;
        final float[] root;

        private Model(final String[] words, final float[] freq) {
            this.words = words;
            this.freq = freq;
            this.root = accumulate(0, words.length, 0);
        }

        static Model load(final Context context, final String lang) throws Exception {
            final ArrayList<String> w = new ArrayList<>(45000);
            final ArrayList<Float> f = new ArrayList<>(45000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("mirkey/zones_" + lang + ".txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    final int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    w.add(line.substring(0, tab));
                    f.add((float) Math.pow(10, Integer.parseInt(line.substring(tab + 1)) / 10.0 - 3));
                }
            }
            final String[] words = w.toArray(new String[0]);
            final float[] freq = new float[f.size()];
            for (int i = 0; i < freq.length; i++) freq[i] = f.get(i);
            return new Model(words, freq);
        }

        float[] next(final String prefix) {
            int lo = 0, hi = words.length;
            while (lo < hi) {
                final int mid = (lo + hi) >>> 1;
                if (words[mid].compareTo(prefix) < 0) lo = mid + 1; else hi = mid;
            }
            int end = lo;
            while (end < words.length && words[end].startsWith(prefix)) end++;
            if (end == lo) return null;
            return accumulate(lo, end, prefix.length());
        }

        private float[] accumulate(final int from, final int to, final int pos) {
            final float[] d = new float[28];
            float total = 0f;
            for (int i = from; i < to; i++) {
                final String s = words[i];
                final float fr = freq[i];
                total += fr;
                if (s.length() == pos) d[26] += fr;
                else d[s.charAt(pos) - 'a'] += fr;
            }
            if (total <= 0f) return null;
            for (int i = 0; i < 27; i++) d[i] /= total;
            if (pos == 0) d[26] = 0f;
            return d;
        }
    }
}
