package dev.civmaker;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline faction-name moderation. Matching never changes the name shown to players. */
final class FactionNames {
    static final String FORMAT_ERROR = "Use 2–24 characters: English letters, numbers, spaces, underscores, or hyphens. Start with a letter or number.";
    static final String CONTENT_ERROR = "That name is not allowed. Choose a name without profanity, slurs, or extremist references.";
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{1,23}");
    private static final Policy POLICY = policy();
    private static final List<Rule> DEFAULT_RULES = defaultRules();
    private final List<Rule> blocked;
    private final List<Rule> allowed;

    private record Policy(List<String> ignoredDictionaryTerms, List<String> wholeWordTerms,
                          List<String> allowedWords, List<String> blockedTerms) {}
    private record Span(int start, int end) {}
    private record Text(String compact, boolean[] boundaries) {
        static Text of(String original) {
            StringBuilder compact = new StringBuilder();
            List<Integer> offsets = new ArrayList<>();
            for (int i = 0; i < original.length(); i++) {
                char ch = original.charAt(i);
                if (asciiLetter(ch) || ch >= '0' && ch <= '9') {
                    compact.append(Character.toLowerCase(ch));
                    offsets.add(i);
                }
            }
            boolean[] boundaries = new boolean[compact.length() + 1];
            boundaries[0] = boundaries[compact.length()] = true;
            for (int i = 1; i < compact.length(); i++) {
                int previous = offsets.get(i - 1), current = offsets.get(i);
                char before = original.charAt(previous), after = original.charAt(current);
                boundaries[i] = current != previous + 1
                        || Character.isDigit(before) != Character.isDigit(after)
                        || Character.isLowerCase(before) && Character.isUpperCase(after)
                        || Character.isUpperCase(before) && Character.isUpperCase(after)
                        && current + 1 < original.length() && Character.isLowerCase(original.charAt(current + 1));
            }
            // Common Xx...xX name decorations must not hide short blocked words or acronyms.
            for (int i = 1; i <= 3 && i < compact.length() && compact.charAt(i - 1) == 'x'; i++) {
                boundaries[i] = true;
            }
            for (int i = compact.length() - 1; i >= compact.length() - 3 && i > 0 && compact.charAt(i) == 'x'; i--) {
                boundaries[i] = true;
            }
            return new Text(compact.toString(), boundaries);
        }
    }

    private record Rule(String word, boolean wholeWord, boolean phrase) {
        List<Span> matches(Text text) {
            List<Span> found = new ArrayList<>();
            for (int start = 0; start + word.length() <= text.compact().length(); start++) {
                if (wholeWord && !text.boundaries()[start]) {
                    continue;
                }
                // Bits represent matched word prefixes. Staying on a prefix permits repeated letters.
                // This bounded state machine also handles ambiguous substitutions such as 1 -> i/l.
                int states = 1;
                for (int end = start; end < text.compact().length(); end++) {
                    int next = 0;
                    char actual = text.compact().charAt(end);
                    for (int letter = 0; letter < word.length(); letter++) {
                        if ((states & (3 << letter)) != 0 && equivalent(word.charAt(letter), actual)) {
                            next |= 1 << (letter + 1);
                        }
                    }
                    states = next;
                    if (states == 0) {
                        break;
                    }
                    if ((states & (1 << word.length())) != 0 && (!wholeWord || text.boundaries()[end + 1])
                            && (phrase || plausibleSpan(text, start, end + 1))) {
                        found.add(new Span(start, end + 1));
                    }
                }
            }
            return found;
        }
    }

    private static boolean plausibleSpan(Text text, int start, int end) {
        if (text.boundaries()[start] || text.boundaries()[end]) {
            return true;
        }
        boolean crossesBoundary = false;
        int previous = start, longest = 0;
        for (int i = start + 1; i < end; i++) {
            if (text.boundaries()[i]) {
                crossesBoundary = true;
                longest = Math.max(longest, i - previous);
                previous = i;
            }
        }
        longest = Math.max(longest, end - previous);
        // Do not accidentally join the tail of "Night" and the head of "Watch".
        // Deliberately spelling a word in one/two-character pieces still matches.
        return !crossesBoundary || longest <= 2;
    }

    FactionNames(List<String> additionalBlockedTerms, List<String> allowedWords) {
        blocked = new ArrayList<>(DEFAULT_RULES);
        additionalBlockedTerms.forEach(term -> addRule(blocked, term, false));
        allowed = new ArrayList<>();
        POLICY.allowedWords().forEach(term -> addRule(allowed, term, false));
        allowedWords.forEach(term -> addRule(allowed, term, false));
    }

    String error(String name) {
        // ASCII-only identifiers reject Unicode lookalikes, zero-width characters, and formatting codes.
        if (name == null || !FORMAT.matcher(name).matches()) {
            return FORMAT_ERROR;
        }
        Text text = Text.of(name);
        List<Span> exceptions = new ArrayList<>();
        allowed.forEach(rule -> exceptions.addAll(rule.matches(text)));
        for (Rule rule : blocked) {
            for (Span match : rule.matches(text)) {
                // An innocent word exempts only the match within it, never the rest of the name.
                if (exceptions.stream().noneMatch(safe -> safe.start() <= match.start() && safe.end() >= match.end())) {
                    return CONTENT_ERROR;
                }
            }
        }
        return null;
    }

    private static boolean equivalent(char expected, char actual) {
        if (expected == actual) {
            return true;
        }
        return switch (actual) {
            case '0' -> expected == 'o';
            case '1' -> expected == 'i' || expected == 'l';
            case '2' -> expected == 'z';
            case '3' -> expected == 'e';
            case '4' -> expected == 'a';
            case '5' -> expected == 's';
            case '6', '9' -> expected == 'g';
            case '7' -> expected == 't';
            case '8' -> expected == 'b';
            case 'v' -> expected == 'u';
            default -> false;
        };
    }

    private static boolean asciiLetter(char ch) {
        return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z';
    }

    private static void addRule(List<Rule> rules, String term, boolean wholeWord) {
        String word = Text.of(term).compact();
        if (!word.isEmpty() && word.length() <= 24) {
            rules.add(new Rule(word, wholeWord, term.chars().anyMatch(ch -> ch == ' ' || ch == '_' || ch == '-')));
        }
    }

    private static List<Rule> defaultRules() {
        List<Rule> rules = new ArrayList<>();
        Set<String> ignored = new HashSet<>(POLICY.ignoredDictionaryTerms());
        Set<String> wholeWords = new HashSet<>(POLICY.wholeWordTerms());
        try (var reader = resource("profanity-en.txt")) {
            for (String term : reader.lines().toList()) {
                term = term.strip().toLowerCase(Locale.ROOT);
                if (!ignored.contains(term)) {
                    addRule(rules, term, Text.of(term).compact().length() < 4 || wholeWords.contains(term));
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not read the bundled faction-name dictionary.", error);
        }
        POLICY.blockedTerms().forEach(term -> addRule(rules, term,
                term.equals("isis") || term.equals("isil") || wholeWords.contains(term)));
        return List.copyOf(rules);
    }

    private static Policy policy() {
        try (var reader = resource("name-policy.json")) {
            return new Gson().fromJson(reader, Policy.class);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read the bundled faction-name policy.", error);
        }
    }

    private static BufferedReader resource(String name) {
        var stream = FactionNames.class.getResourceAsStream("/moderation/" + name);
        if (stream == null) {
            throw new IllegalStateException("Missing faction-name filter resource: " + name);
        }
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
