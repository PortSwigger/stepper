package com.xreous.stepperng.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class RegexGenerator {

    private static final String REGEX_SPECIAL = "\\[](){}.*+?^$|";

    private static final Pattern JSON_KEY_DOUBLE = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_.-]*)\"\\s*:\\s*\"?\\s*$");
    private static final Pattern JSON_KEY_SINGLE = Pattern.compile("'([A-Za-z_][A-Za-z0-9_.-]*)'\\s*:\\s*'?\\s*$");
    private static final Pattern HEADER_FULL_LINE = Pattern.compile("^([A-Za-z][A-Za-z0-9-]*):\\s*$");
    private static final Pattern HEADER_TAIL = Pattern.compile("([A-Za-z][A-Za-z0-9-]*):\\s*$");
    private static final Pattern HEADER_LINE_START = Pattern.compile("^[A-Za-z][A-Za-z0-9-]*:\\s+");
    private static final Pattern ASSIGNMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_.-]*)=\\s*\"?\\s*$");
    private static final Pattern COOKIE_HEADER_PREFIX = Pattern.compile("^(Set-Cookie|Cookie)\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    public static String escapeRegex(String literal) {
        StringBuilder sb = new StringBuilder();
        for (char c : literal.toCharArray()) {
            if (REGEX_SPECIAL.indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return sb.toString();
    }

    public static String generateRegex(String fullText, String selectedText, int selectionStart) {
        if (selectedText == null || selectedText.isEmpty()) return "";
        if (fullText == null || fullText.isEmpty()) return "";

        int trimStart = 0;
        int trimEnd = selectedText.length();
        while (trimStart < trimEnd && (selectedText.charAt(trimStart) == '\r' || selectedText.charAt(trimStart) == '\n'))
            trimStart++;
        while (trimEnd > trimStart && (selectedText.charAt(trimEnd - 1) == '\r' || selectedText.charAt(trimEnd - 1) == '\n'))
            trimEnd--;
        if (trimStart >= trimEnd) return "";
        selectionStart += trimStart;
        selectedText = selectedText.substring(trimStart, trimEnd);

        String jsonAnchor = tryJsonAnchor(fullText, selectedText, selectionStart);
        if (jsonAnchor != null) return jsonAnchor;

        String cookieAnchor = tryCookieHeaderAnchor(fullText, selectedText, selectionStart);
        if (cookieAnchor != null) return cookieAnchor;

        int selectionEnd = selectionStart + selectedText.length();
        int lineEnd = findLineEnd(fullText, selectionStart);
        boolean selectionWithinSingleLine = selectionEnd <= lineEnd;

        if (selectionWithinSingleLine) {
            return generateSameLineRegex(fullText, selectedText, selectionStart, selectionEnd);
        } else {
            return generateMultiLineRegex(fullText, selectedText, selectionStart, selectionEnd);
        }
    }

    private static String generateSameLineRegex(String fullText, String selectedText,
                                                  int absStart, int absEnd) {
        String prefix = findNearestPrefix(fullText, absStart);
        String suffix = findNearestSuffix(fullText, absEnd);

        if (!prefix.isEmpty() && !suffix.isEmpty()) {
            return escapeRegex(prefix) + "([^\\r\\n]*?)" + escapeRegex(suffix);
        }

        if (!prefix.isEmpty()) {
            int lineEnd = findLineEnd(fullText, absEnd);
            String remaining = fullText.substring(absEnd, lineEnd);
            if (remaining.trim().isEmpty()) {
                return escapeRegex(prefix) + "([^\\r\\n]+)";
            }
            return escapeRegex(prefix) + "([^\\r\\n]*?)\\s";
        }

        if (!suffix.isEmpty()) {
            return "([^\\s]+?)" + escapeRegex(suffix);
        }

        return generateFromStructuralContext(fullText, selectedText, absStart, absEnd);
    }

    private static String generateMultiLineRegex(String fullText, String selectedText,
                                                   int absStart, int absEnd) {
        String prefix = findNearestPrefix(fullText, absStart);
        String suffix = findNearestSuffix(fullText, absEnd);

        if (!prefix.isEmpty() && !suffix.isEmpty()) {
            return escapeRegex(prefix) + "([\\s\\S]*?)" + escapeRegex(suffix);
        }
        if (!prefix.isEmpty()) {
            String nextAnchor = grabNextNonEmptyLinePrefix(fullText, absEnd, 20);
            if (!nextAnchor.isEmpty()) {
                return escapeRegex(prefix) + "([\\s\\S]*?)" + escapeRegex(nextAnchor);
            }
            return escapeRegex(prefix) + "([\\s\\S]*?)\\r?\\n";
        }
        return generateFromStructuralContext(fullText, selectedText, absStart, absEnd);
    }

    private static String generateFromStructuralContext(String fullText, String selectedText,
                                                         int absStart, int absEnd) {
        String prevAnchor = grabPreviousLineEnd(fullText, absStart, 30);
        String nextAnchor = grabNextNonEmptyLinePrefix(fullText, absEnd, 30);

        if (!prevAnchor.isEmpty() && !nextAnchor.isEmpty()) {
            String capture = selectedText.contains("\n") ? "([\\s\\S]*?)" : "([^\\r\\n]+?)";
            return escapeRegex(prevAnchor) + "\\s+" + capture + "\\s*" + escapeRegex(nextAnchor);
        }
        if (!prevAnchor.isEmpty()) {
            return escapeRegex(prevAnchor) + "\\s+([^\\r\\n]+)";
        }
        if (!nextAnchor.isEmpty()) {
            return "([^\\r\\n]+?)\\s*" + escapeRegex(nextAnchor);
        }
        return "";
    }

    private static String findNearestPrefix(String fullText, int selStart) {
        int lineStart = findLineStart(fullText, selStart);
        String before = fullText.substring(lineStart, selStart);
        if (before.isEmpty()) return "";

        // HTTP header lines: prefer the full "Name: " prefix over any colon found by the
        // generic backward scan, so URL/port colons inside the value don't become the anchor.
        if (HEADER_LINE_START.matcher(before).find()) return before;

        int bestKeyStart = -1;
        for (int i = before.length() - 1; i >= 0; i--) {
            char c = before.charAt(i);
            if (c == '"' || c == '\'') {
                String sub = before.substring(0, i + 1);
                int keyMatch = findJsonKeyAnchor(sub);
                if (keyMatch >= 0) {
                    bestKeyStart = keyMatch;
                    break;
                }
            }
            if (c == ':') {
                String sub = before.substring(0, i + 1);
                int keyMatch = findJsonKeyAnchor(sub);
                if (keyMatch >= 0) {
                    bestKeyStart = keyMatch;
                    break;
                }
                int headerMatch = findHeaderAnchor(sub);
                if (headerMatch >= 0) {
                    bestKeyStart = headerMatch;
                    break;
                }
            }
            if (c == '>') {
                int tagStart = findTagAnchor(before, i);
                if (tagStart >= 0) {
                    bestKeyStart = tagStart;
                    break;
                }
            }
            if (c == '=') {
                int eqAnchor = findAssignmentAnchor(before, i);
                if (eqAnchor >= 0) {
                    bestKeyStart = eqAnchor;
                    break;
                }
            }
        }

        if (bestKeyStart >= 0) {
            String anchor = before.substring(bestKeyStart);
            if (anchorKeyLength(anchor) < 3 && bestKeyStart > 0) {
                String extended = extendAnchorBackward(before, bestKeyStart);
                if (extended != null) return extended;
            }
            return anchor;
        }

        int lastDelim = -1;
        for (int i = before.length() - 1; i >= 0; i--) {
            char c = before.charAt(i);
            if (c == ',' || c == ';' || c == '&' || c == '?' || c == '\t') {
                lastDelim = i;
                break;
            }
        }
        if (lastDelim >= 0 && lastDelim < before.length() - 1) {
            return before.substring(lastDelim + 1);
        }

        if (before.length() <= 60) {
            return before;
        }
        return "";
    }

    private static int findJsonKeyAnchor(String before) {
        Matcher m = JSON_KEY_DOUBLE.matcher(before);
        if (m.find()) return m.start();
        m = JSON_KEY_SINGLE.matcher(before);
        if (m.find()) return m.start();
        return -1;
    }

    private static int findHeaderAnchor(String before) {
        Matcher m = HEADER_FULL_LINE.matcher(before.trim());
        if (m.find()) return before.length() - before.stripLeading().length();
        m = HEADER_TAIL.matcher(before);
        if (m.find()) return m.start();
        return -1;
    }

    private static int findTagAnchor(String before, int closePos) {
        for (int i = closePos - 1; i >= 0; i--) {
            if (before.charAt(i) == '<') {
                return i;
            }
        }
        return closePos;
    }

    private static int findAssignmentAnchor(String before, int eqPos) {
        Matcher m = ASSIGNMENT.matcher(before.substring(0, eqPos + 1));
        if (m.find()) return m.start();
        return -1;
    }

    private static String findNearestSuffix(String fullText, int selEnd) {
        int lineEnd = findLineEnd(fullText, selEnd);
        String after = fullText.substring(selEnd, lineEnd);
        if (after.isEmpty()) return "";

        for (int i = 0; i < after.length(); i++) {
            char c = after.charAt(i);
            if (c == '"' || c == '\'' || c == '<' || c == ',' || c == ';'
                    || c == '}' || c == ')' || c == ']' || c == '&') {
                int end = i + 1;
                if ((c == '"' || c == '\'') && end < after.length()) {
                    char next = after.charAt(end);
                    if (next == ',' || next == '}' || next == ']') {
                        end++;
                        if (next == ',' && end < after.length() && after.charAt(end) == '"') {
                            int keyEnd = after.indexOf('"', end + 1);
                            if (keyEnd > 0 && keyEnd - end < 30) {
                                end = keyEnd + 1;
                            }
                        }
                    }
                }
                return after.substring(0, end);
            }
        }

        if (after.length() <= 30) return after;
        return "";
    }

    static int findLineStart(String text, int pos) {
        for (int i = pos - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n' || text.charAt(i) == '\r') return i + 1;
        }
        return 0;
    }

    static int findLineEnd(String text, int pos) {
        for (int i = pos; i < text.length(); i++) {
            if (text.charAt(i) == '\r' || text.charAt(i) == '\n') return i;
        }
        return text.length();
    }

    private static String grabPreviousLineEnd(String text, int pos, int maxChars) {
        int cursor = findLineStart(text, pos);
        for (int attempt = 0; attempt < 5 && cursor > 0; attempt++) {
            int prevLineEnd = cursor - 1;
            if (prevLineEnd > 0 && text.charAt(prevLineEnd - 1) == '\r') prevLineEnd--;
            int prevLineStart = findLineStart(text, prevLineEnd);
            String prev = text.substring(prevLineStart, prevLineEnd);
            if (!prev.trim().isEmpty()) {
                int take = Math.min(prev.length(), maxChars);
                return prev.substring(prev.length() - take);
            }
            cursor = prevLineStart;
        }
        return "";
    }

    private static String grabNextNonEmptyLinePrefix(String text, int pos, int maxChars) {
        int cursor = findLineEnd(text, pos);
        for (int attempt = 0; attempt < 5 && cursor < text.length(); attempt++) {
            int next = cursor;
            if (next < text.length() && text.charAt(next) == '\r') next++;
            if (next < text.length() && text.charAt(next) == '\n') next++;
            if (next >= text.length()) return "";
            int nextEnd = findLineEnd(text, next);
            String line = text.substring(next, nextEnd);
            if (!line.trim().isEmpty()) {
                int take = Math.min(line.length(), maxChars);
                return line.substring(0, take);
            }
            cursor = nextEnd;
        }
        return "";
    }

    private static int anchorKeyLength(String anchor) {
        int i = 0;
        while (i < anchor.length() && (anchor.charAt(i) == '"' || anchor.charAt(i) == '\'')) i++;
        int start = i;
        while (i < anchor.length() && (Character.isLetterOrDigit(anchor.charAt(i))
                || anchor.charAt(i) == '_' || anchor.charAt(i) == '-' || anchor.charAt(i) == '.')) {
            i++;
        }
        return i - start;
    }

    private static String extendAnchorBackward(String before, int bestKeyStart) {
        int scan = bestKeyStart - 1;
        while (scan >= 0 && Character.isWhitespace(before.charAt(scan))) scan--;
        if (scan < 0) return null;

        int limit = Math.max(0, bestKeyStart - 60);
        int extStart = -1;

        for (int i = scan; i >= limit; i--) {
            char c = before.charAt(i);
            if (c == ',' || c == ';' || c == '{' || c == '[' || c == '&' || c == '\t') {
                extStart = i;
                break;
            }
        }

        if (extStart >= 0) {
            String extended = before.substring(extStart);
            if (extended.length() > 80) {
                extended = before.substring(bestKeyStart > 20 ? bestKeyStart - 20 : 0);
            }
            return extended;
        }

        int take = Math.min(bestKeyStart, 30);
        if (take > 0) {
            return before.substring(bestKeyStart - take);
        }
        return null;
    }

    /** Resolves the JSON path of {@code selectedText} to an unambiguous anchored regex, or {@code null} if the selection isn't a unique non-array leaf. */
    private static String tryJsonAnchor(String text, String selectedText, int selectionStart) {
        if (selectedText.indexOf('\n') >= 0 || selectedText.indexOf('\r') >= 0) return null;
        String regex = tryJsonAnchorForText(text, selectedText, text);
        if (regex != null) return regex;

        int bodyStart = findHttpBodyStart(text);
        if (bodyStart >= 0 && selectionStart >= bodyStart) {
            return tryJsonAnchorForText(text.substring(bodyStart), selectedText, text);
        }
        return null;
    }

    private static String tryJsonAnchorForText(String jsonText, String selectedText, String matchText) {
        JsonElement root;
        try {
            root = JsonParser.parseString(jsonText);
        } catch (Exception e) { return null; }
        if (root == null || (!root.isJsonObject() && !root.isJsonArray())) return null;

        List<JsonMatch> matches = new ArrayList<>();
        findJsonPaths(root, new ArrayDeque<>(), selectedText, matches);
        if (matches.size() != 1) return null;

        JsonMatch match = matches.get(0);
        if (match.path.size() < 2) return null;
        for (String seg : match.path) if (seg.startsWith("[")) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < match.path.size(); i++) {
            if (i > 0) sb.append("[\\s\\S]*?");
            sb.append(escapeRegex("\"" + match.path.get(i) + "\"")).append("\\s*:\\s*");
        }
        // String values are delimited by quotes; numbers/booleans/null are bounded by JSON structural chars.
        sb.append(match.isString ? "\"([^\"]*)\"" : "([^,}\\]\\s]+)");
        String regex = sb.toString();

        try {
            Matcher m = Pattern.compile(regex).matcher(matchText);
            if (m.find() && selectedText.equals(m.group(1))) return regex;
        } catch (PatternSyntaxException ignored) {}
        return null;
    }

    private static int findHttpBodyStart(String text) {
        int crlf = text.indexOf("\r\n\r\n");
        int lf = text.indexOf("\n\n");
        if (crlf < 0) return lf >= 0 ? lf + 2 : -1;
        if (lf < 0) return crlf + 4;
        return crlf < lf ? crlf + 4 : lf + 2;
    }

    private record JsonMatch(List<String> path, boolean isString) {}

    private static void findJsonPaths(JsonElement node, Deque<String> path, String target, List<JsonMatch> out) {
        if (node.isJsonObject()) {
            for (var entry : node.getAsJsonObject().entrySet()) {
                path.addLast(entry.getKey());
                findJsonPaths(entry.getValue(), path, target, out);
                path.removeLast();
            }
        } else if (node.isJsonArray()) {
            var arr = node.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                path.addLast("[" + i + "]");
                findJsonPaths(arr.get(i), path, target, out);
                path.removeLast();
            }
        } else if (node.isJsonPrimitive()) {
            JsonPrimitive prim = node.getAsJsonPrimitive();
            if (target.equals(prim.getAsString())) {
                out.add(new JsonMatch(new ArrayList<>(path), prim.isString()));
            }
        }
    }

    /** Anchors selections inside {@code Cookie:}/{@code Set-Cookie:} headers on the enclosing {@code name=value} pair. */
    private static String tryCookieHeaderAnchor(String fullText, String selectedText, int selectionStart) {
        int lineStart = findLineStart(fullText, selectionStart);
        int lineEnd = findLineEnd(fullText, selectionStart);
        int selectionEnd = selectionStart + selectedText.length();
        if (selectionEnd > lineEnd) return null;

        String line = fullText.substring(lineStart, lineEnd);
        Matcher hm = COOKIE_HEADER_PREFIX.matcher(line);
        if (!hm.find()) return null;
        String headerName = hm.group(1);
        int valueStart = hm.end();

        int selInLine = selectionStart - lineStart;
        int selEndInLine = selectionEnd - lineStart;
        if (selInLine < valueStart) return null;

        int pairStart = selInLine;
        while (pairStart > valueStart && line.charAt(pairStart - 1) != ';') pairStart--;
        while (pairStart < line.length() && Character.isWhitespace(line.charAt(pairStart))) pairStart++;

        int pairEnd = selEndInLine;
        while (pairEnd < line.length() && line.charAt(pairEnd) != ';') pairEnd++;
        int trimmedEnd = pairEnd;
        while (trimmedEnd > pairStart && Character.isWhitespace(line.charAt(trimmedEnd - 1))) trimmedEnd--;

        String pair = line.substring(pairStart, trimmedEnd);
        int eq = pair.indexOf('=');
        if (eq <= 0) return null;
        String name = pair.substring(0, eq);
        String value = pair.substring(eq + 1);
        if (!COOKIE_NAME.matcher(name).matches()) return null;
        if (!selectedText.equals(value) && !selectedText.equals(value.trim())) return null;

        boolean firstPair = pairStart == valueStart;
        boolean isSetCookie = "Set-Cookie".equalsIgnoreCase(headerName);

        String regex;
        if (isSetCookie && firstPair) {
            regex = escapeRegex(headerName) + ":\\s*" + escapeRegex(name) + "=([^;\\r\\n]*)";
        } else {
            // Negative lookbehind prevents matching a name that's a suffix of another (e.g. "id" inside "sid").
            regex = escapeRegex(headerName) + ":[^\\r\\n]*?(?<![A-Za-z0-9_.-])"
                    + escapeRegex(name) + "=([^;\\r\\n]*)";
        }

        try {
            Matcher m = Pattern.compile(regex).matcher(fullText);
            if (m.find() && selectedText.equals(m.group(1).trim())) return regex;
        } catch (PatternSyntaxException ignored) {}
        return null;
    }
}
