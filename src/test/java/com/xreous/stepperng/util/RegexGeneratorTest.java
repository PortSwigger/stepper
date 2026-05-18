package com.xreous.stepperng.util;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RegexGeneratorTest {

    private static String capture(String regex, String text) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (!m.find()) return null;
        return m.groupCount() >= 1 ? m.group(1) : m.group();
    }

    private static String runOn(String text, String selected) {
        int idx = text.indexOf(selected);
        assertTrue(idx >= 0, "selection must exist in text");
        return RegexGenerator.generateRegex(text, selected, idx);
    }

    @Test
    void emptyInputs() {
        assertEquals("", RegexGenerator.generateRegex(null, "x", 0));
        assertEquals("", RegexGenerator.generateRegex("x", null, 0));
        assertEquals("", RegexGenerator.generateRegex("x", "", 0));
        assertEquals("", RegexGenerator.generateRegex("", "x", 0));
    }

    @Test
    void jsonFlatKey() {
        String text = "{\"token\":\"abc123\"}";
        String regex = runOn(text, "abc123");
        assertEquals("abc123", capture(regex, text));
    }

    @Test
    void jsonNestedKey() {
        String text = "{\"data\":{\"token\":\"xyz\"}}";
        String regex = runOn(text, "xyz");
        assertEquals("xyz", capture(regex, text));
    }

    @Test
    void jsonAmbiguousKeyAtDifferentPaths() {
        // The current textual lookback alone matches the FIRST "id" - JSON-aware anchor
        // is required to disambiguate by parent key.
        String text = "{\"user\":{\"id\":\"u1\"},\"session\":{\"id\":\"s1\"}}";
        String regex = runOn(text, "s1");
        assertEquals("s1", capture(regex, text));
    }

    @Test
    void httpHeader() {
        String text = "GET /a HTTP/1.1\r\nAuthorization: Bearer abc.def.ghi\r\nHost: x\r\n";
        String regex = runOn(text, "abc.def.ghi");
        assertEquals("abc.def.ghi", capture(regex, text));
    }

    @Test
    void xmlTag() {
        String text = "<root><csrf>tok-123</csrf></root>";
        String regex = runOn(text, "tok-123");
        assertEquals("tok-123", capture(regex, text));
    }

    @Test
    void formEncoded() {
        String text = "user=alice&token=zzz&next=/";
        String regex = runOn(text, "zzz");
        assertEquals("zzz", capture(regex, text));
    }

    @Test
    void selectionTrimsLeadingTrailingNewlines() {
        String text = "{\"k\":\"v\"}";
        String result = RegexGenerator.generateRegex(text, "\nv\n", text.indexOf("v") - 1);
        assertEquals("v", capture(result, text));
    }

    @Test
    void multilineSelection() {
        String text = "<pre>line1\nline2\nline3</pre>";
        String selected = "line1\nline2\nline3";
        int idx = text.indexOf(selected);
        String regex = RegexGenerator.generateRegex(text, selected, idx);
        assertNotNull(capture(regex, text));
    }

    @Test
    void escapeRegexHandlesSpecialChars() {
        assertEquals("\\.\\*\\+", RegexGenerator.escapeRegex(".*+"));
        assertEquals("plain", RegexGenerator.escapeRegex("plain"));
    }

    @Test
    void headerWithColonsInValue() {
        String text = "HTTP/1.1 302 Found\r\nLocation: https://example.com:8080/y\r\n\r\n";
        String regex = runOn(text, "8080");
        assertEquals("8080", capture(regex, text));
    }

    @Test
    void headerWithMultipleColonsAndPriorHostInResponse() {
        // Without line-start header preference, the backward colon-scan anchors on `com:`
        // and matches the prior URL's port (`1`) instead of the selected `8080`.
        String text = "User-Agent: foo://bar.com:1\r\nLocation: https://example.com:8080\r\n";
        String regex = runOn(text, "8080");
        assertEquals("8080", capture(regex, text));
    }

    @Test
    void jsonNumberLeaf() {
        String text = "{\"data\":{\"count\":42}}";
        String regex = runOn(text, "42");
        assertEquals("42", capture(regex, text));
    }

    @Test
    void jsonAmbiguousNumberByPath() {
        // Same leaf key under two parents; textual lookback alone captures the first one.
        String text = "{\"a\":{\"count\":1},\"b\":{\"count\":42}}";
        String regex = runOn(text, "42");
        assertEquals("42", capture(regex, text));
    }

    @Test
    void jsonAmbiguousBooleanByPath() {
        String text = "{\"a\":{\"flag\":false},\"b\":{\"flag\":true}}";
        String regex = runOn(text, "true");
        assertEquals("true", capture(regex, text));
    }

    @Test
    void httpResponseJsonBodyUsesJsonPathAnchor() {
        String text = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"
                + "{\n"
                + "  \"user\": {\n"
                + "    \"id\": \"u1\"\n"
                + "  },\n"
                + "  \"session\": {\n"
                + "    \"id\": \"s1\"\n"
                + "  }\n"
                + "}";
        String regex = runOn(text, "s1");
        assertEquals("s1", capture(regex, text));
        assertTrue(regex.contains("session"), regex);
    }

    @Test
    void httpRequestJsonBodyUsesJsonPathAnchor() {
        String text = "POST /api/login HTTP/1.1\nHost: example.com\nContent-Type: application/json\n\n"
                + "{\"credentials\":{\"username\":\"alice\",\"token\":\"abc123\"}}";
        String regex = runOn(text, "abc123");
        assertEquals("abc123", capture(regex, text));
    }

    @Test
    void setCookieFirstPair() {
        String text = "HTTP/1.1 200 OK\r\nSet-Cookie: SESSIONID=abc123; Path=/; HttpOnly\r\n\r\n";
        String regex = runOn(text, "abc123");
        assertEquals("abc123", capture(regex, text));
        // Anchor should be tight to header + name, not pollute with trailing attrs.
        assertTrue(regex.startsWith("Set-Cookie:"), regex);
        assertTrue(regex.contains("SESSIONID="), regex);
    }

    @Test
    void setCookieValueRobustToAttrChanges() {
        String original = "Set-Cookie: SID=tok-1; Path=/a; Max-Age=60\r\n";
        String mutated  = "Set-Cookie: SID=tok-1; Domain=example.com; Secure\r\n";
        String regex = runOn(original, "tok-1");
        assertEquals("tok-1", capture(regex, original));
        assertEquals("tok-1", capture(regex, mutated));
    }

    @Test
    void setCookieAttributeValueIsolated() {
        // Selecting the Path attribute value should NOT bake the volatile cookie value into the regex.
        String original = "Set-Cookie: SID=eyJhbGc.foo.bar; Path=/admin; HttpOnly\r\n";
        String rotated  = "Set-Cookie: SID=DIFFERENT_TOKEN; Path=/admin; HttpOnly\r\n";
        String regex = runOn(original, "/admin");
        assertEquals("/admin", capture(regex, original));
        assertEquals("/admin", capture(regex, rotated));
    }

    @Test
    void cookieRequestHeaderRobustToOtherPairs() {
        // Cookie request header: selecting one cookie's value must not anchor on neighbours.
        String original = "GET / HTTP/1.1\r\nCookie: a=1; b=2; SESSION=abc\r\n\r\n";
        String mutated  = "GET / HTTP/1.1\r\nCookie: a=99; SESSION=abc; tracking=xyz\r\n\r\n";
        String regex = runOn(original, "abc");
        assertEquals("abc", capture(regex, original));
        assertEquals("abc", capture(regex, mutated));
    }

    @Test
    void cookieHeaderCaseInsensitive() {
        String text = "set-cookie: token=ZZZ; Path=/\r\n";
        String regex = runOn(text, "ZZZ");
        assertEquals("ZZZ", capture(regex, text));
    }

    @Test
    void setCookiePartialValueFallsBackToGeneric() {
        // Partial selection inside a cookie value shouldn't trigger the pair-level anchor;
        // generic path still produces a working regex.
        String text = "Set-Cookie: SID=abc.def.ghi; Path=/\r\n";
        String regex = runOn(text, "def");
        assertEquals("def", capture(regex, text));
    }

    @Test
    void multipleSetCookieHeadersNamedDifferently() {
        String text = "HTTP/1.1 200 OK\r\n"
                + "Set-Cookie: CSRF=aaa; Path=/\r\n"
                + "Set-Cookie: SESSION=bbb; HttpOnly\r\n\r\n";
        String regex = runOn(text, "bbb");
        assertEquals("bbb", capture(regex, text));
    }
}
