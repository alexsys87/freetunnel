package org.freetunnel.android;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A license text or the third-party notices as the licenses card shows them. The files are written for a page 80 columns wide: each
 * paragraph is broken at 80 by hand, and the notices are Markdown. In a box a phone or a TV is only about 55 characters across, the
 * text wraps again, and every source line becomes a long line and a stub of two or three words, with the Markdown's own marks (#,
 * backticks, [text](target)) in it. Lines of a paragraph are joined here, so that the box wraps them once, and the marks are taken out;
 * what has a line of its own (a heading, a list item, a table row, a short line) keeps it. Plain Java, so that a JVM tests it.
 */
final class LicenseText {
    private LicenseText() { }

    /** The name the card lists a file by: the file's own, without the folder it is in (licenses/common/zlib.txt is zlib.txt). */
    static String name(String asset) {
        return asset.substring(asset.lastIndexOf('/') + 1);
    }

    /** A plain-text line this long ends where the source wrapped it; a shorter one ends where its author did. */
    static final int WRAPPED_AT = 50;

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");
    // "Source: https://…", "Version: 1.3.1": a line that names what it says, which starts a line of its own however long the one before.
    private static final Pattern KEYED = Pattern.compile("^\\s*[A-Z][A-Za-z ]{1,24}:\\s+\\S.*$");
    // ======= or ------- or *****: a line of marks that only separates.
    private static final Pattern DIVIDER = Pattern.compile("^\\s*[=\\-*_~#+.]{3,}\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    // (a), a., 1., 1) and (1): what starts an item of a numbered or lettered list in a license.
    private static final Pattern ITEM = Pattern.compile("^(\\s*)(\\(?[A-Za-z0-9]{1,3}[.)])\\s+(.*)$");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|?\\s*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]*)\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\s]+)>");
    private static final Pattern CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern ESCAPE = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|<>])");

    /** The text with the lines of each paragraph joined; {@code markdown} for a .md file, whose marks are taken out too. */
    static String reflow(String text, boolean markdown) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1);
        List<String> out = new ArrayList<>();
        // The block being gathered: its first line's indent, the text so far, and whether a following line may be joined to it.
        StringBuilder block = null;
        String lead = "";
        boolean joinable = false;
        boolean fenced = false;
        for (String line : lines) {
            if (markdown && FENCE.matcher(line).matches()) {
                flush(out, block, lead, markdown);
                block = null;
                fenced = !fenced;
                continue;
            }
            if (fenced) { out.add(line); continue; }
            if (line.trim().isEmpty()) {
                flush(out, block, lead, markdown);
                block = null;
                // One blank line however many the file had.
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
                continue;
            }
            if (markdown && RULE.matcher(line).matches()) {
                // A horizontal rule: a break between paragraphs, and nothing to show.
                flush(out, block, lead, markdown);
                block = null;
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
                continue;
            }
            if (markdown) {
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    flush(out, block, lead, markdown);
                    block = null;
                    if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
                    out.add(inline(heading.group(1)));
                    joinable = false;
                    continue;
                }
                if (TABLE_RULE.matcher(line).matches()) {
                    // The line under a table's header: it only says where the columns are.
                    continue;
                }
                if (TABLE_ROW.matcher(line).matches()) {
                    flush(out, block, lead, markdown);
                    block = null;
                    out.add(row(line));
                    joinable = false;
                    continue;
                }
            }
            if (!markdown && DIVIDER.matcher(line).matches()) {
                // Its own line, and the one after it starts another.
                flush(out, block, lead, markdown);
                block = null;
                out.add(line.trim());
                joinable = false;
                continue;
            }
            Matcher bullet = markdown ? BULLET.matcher(line) : null;
            Matcher item = ITEM.matcher(line);
            boolean starts = bullet != null && bullet.matches() || item.matches() || block == null || !markdown && KEYED.matcher(line).matches();
            if (!starts && !markdown && !joinable) starts = true;
            if (starts) {
                flush(out, block, lead, markdown);
                block = new StringBuilder();
                if (bullet != null && bullet.matches()) {
                    lead = bullet.group(1);
                    block.append("• ").append(bullet.group(2).trim());
                } else {
                    lead = indent(line);
                    block.append(line.trim());
                }
            } else {
                block.append(' ').append(line.trim());
            }
            // A markdown paragraph is joined whatever the line's length; a plain text one only where the source wrapped it.
            joinable = markdown || line.trim().length() >= WRAPPED_AT;
        }
        flush(out, block, lead, markdown);
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        StringBuilder text2 = new StringBuilder();
        for (int i = 0; i < out.size(); i++) text2.append(out.get(i)).append('\n');
        return text2.toString();
    }

    private static void flush(List<String> out, StringBuilder block, String lead, boolean markdown) {
        if (block == null) return;
        String shown = markdown ? inline(block.toString()) : block.toString();
        // A little of the source's indent is kept for what is set in (a license's clauses), and no more than the box has room for.
        out.add(lead.substring(0, Math.min(lead.length(), 6)) + shown);
    }

    private static String indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return line.substring(0, n);
    }

    // | a | b | as a line: the cells with a dot between.
    private static String row(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] cells = t.split("\\|", -1);
        StringBuilder out = new StringBuilder();
        for (String cell : cells) {
            String c = inline(cell.trim());
            if (c.isEmpty()) continue;
            if (out.length() > 0) out.append("  ·  ");
            out.append(c);
        }
        return out.toString();
    }

    // Markdown's marks within a line: a link is its words (and its address too, if it is one on the web, which is where a reader
    // is sent; a link to a file or a heading of the document is only its words), code and emphasis are their text.
    static String inline(String text) {
        String s = IMAGE.matcher(text).replaceAll("$1");
        Matcher links = LINK.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (links.find()) {
            out.append(s, last, links.start());
            String target = links.group(2);
            out.append(links.group(1));
            if (target.startsWith("http://") || target.startsWith("https://")) out.append(" (").append(target).append(')');
            last = links.end();
        }
        out.append(s, last, s.length());
        s = out.toString();
        s = AUTOLINK.matcher(s).replaceAll("$1");
        s = CODE.matcher(s).replaceAll("$1");
        s = BOLD.matcher(s).replaceAll("$2");
        s = ESCAPE.matcher(s).replaceAll("$1");
        return s.replaceAll(" {2,}", " ").trim();
    }
}
