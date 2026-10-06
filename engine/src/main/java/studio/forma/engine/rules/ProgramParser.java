package studio.forma.engine.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses FORMA's compact, indentation-based rule program text.
 *
 * <pre>
 * # comment
 * sequence detailing
 *   prl windows steps=1
 *     rule bay "AWE" -&gt; "*N*" p=0.8 sym=rotate   # optional description
 *   one gardens steps=300
 *     rule seed "T E" -&gt; "G E" p=0.05
 * </pre>
 *
 * Node lines: {@code one|all|prl|sequence|markov <name> [steps=N] [sym=S]}. A {@code sym} on a
 * rule node is the default for its rules. Rule lines: {@code rule <id> "<in>" -> "<out>"
 * [p=X] [sym=S] [# description]}. Children are indented by two spaces more than their parent.
 */
public final class ProgramParser {
    private ProgramParser() {}

    public static final int MAX_RULES = 200;
    public static final int MAX_SOURCE = 20_000;

    private static final class Line {
        final int no, indent;
        final String text, comment;

        Line(int no, int indent, String text, String comment) {
            this.no = no;
            this.indent = indent;
            this.text = text;
            this.comment = comment;
        }
    }

    public static Node parse(String source, Legend legend) {
        if (source == null || source.isBlank()) throw new RuleValidationException("rule program is empty");
        if (source.length() > MAX_SOURCE) throw new RuleValidationException("rule program is longer than " + MAX_SOURCE + " characters");
        List<Line> lines = new ArrayList<>();
        String[] raw = source.replace("\t", "  ").split("\r?\n");
        for (int i = 0; i < raw.length; i++) {
            String l = raw[i];
            String comment = "";
            int hash = indexOfCommentStart(l);
            if (hash >= 0) {
                comment = l.substring(hash + 1).trim();
                l = l.substring(0, hash);
            }
            if (l.isBlank()) continue;
            int indent = 0;
            while (indent < l.length() && l.charAt(indent) == ' ') indent++;
            if (indent % 2 != 0) throw err(i + 1, "indentation must be a multiple of two spaces");
            lines.add(new Line(i + 1, indent / 2, l.trim(), comment));
        }
        int[] pos = {0};
        int[] ruleCount = {0};
        if (lines.get(0).indent != 0) throw err(lines.get(0).no, "the first node must not be indented");
        Node root = parseNode(lines, pos, legend, ruleCount);
        if (pos[0] < lines.size()) throw err(lines.get(pos[0]).no, "only one top-level node is allowed (wrap several in a sequence)");
        return root;
    }

    private static int indexOfCommentStart(String l) {
        boolean inQuote = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (c == '#' && !inQuote) return i;
        }
        return -1;
    }

    private static RuleValidationException err(int line, String msg) {
        return new RuleValidationException("line " + line + ": " + msg);
    }

    private static Node parseNode(List<Line> lines, int[] pos, Legend legend, int[] ruleCount) {
        Line head = lines.get(pos[0]++);
        String[] tok = head.text.split("\\s+");
        String type = tok[0];
        if (tok.length < 2) throw err(head.no, "node '" + type + "' needs a name");
        String name = tok[1];
        int steps = 0;
        Symmetry sym = Symmetry.FULL;
        for (int i = 2; i < tok.length; i++) {
            String t = tok[i];
            if (t.startsWith("steps=")) {
                try {
                    steps = Integer.parseInt(t.substring(6));
                } catch (NumberFormatException e) {
                    throw err(head.no, "steps must be an integer");
                }
                if (steps < 0 || steps > 1_000_000) throw err(head.no, "steps must be between 0 and 1000000");
            } else if (t.startsWith("sym=")) {
                try {
                    sym = Symmetry.parse(t.substring(4));
                } catch (RuleValidationException e) {
                    throw err(head.no, e.getMessage());
                }
            } else throw err(head.no, "unknown attribute '" + t + "'");
        }
        int childIndent = head.indent + 1;
        switch (type) {
            case "sequence", "markov" -> {
                List<Node> kids = new ArrayList<>();
                while (pos[0] < lines.size() && lines.get(pos[0]).indent >= childIndent) {
                    Line c = lines.get(pos[0]);
                    if (c.indent != childIndent) throw err(c.no, "unexpected indentation");
                    if (c.text.startsWith("rule ")) throw err(c.no, "rules belong inside one/all/prl nodes, not a " + type);
                    kids.add(parseNode(lines, pos, legend, ruleCount));
                }
                if (kids.isEmpty()) throw err(head.no, type + " '" + name + "' has no children");
                return type.equals("sequence") ? new SequenceNode(name, kids) : new MarkovNode(name, kids);
            }
            case "one", "all", "prl" -> {
                List<Rule> rules = new ArrayList<>();
                while (pos[0] < lines.size() && lines.get(pos[0]).indent >= childIndent) {
                    Line c = lines.get(pos[0]++);
                    if (c.indent != childIndent) throw err(c.no, "unexpected indentation");
                    if (!c.text.startsWith("rule ")) throw err(c.no, "only rule lines may appear inside a " + type + " node");
                    if (++ruleCount[0] > MAX_RULES) throw err(c.no, "too many rules (limit " + MAX_RULES + ")");
                    rules.add(parseRule(c, sym, legend));
                }
                if (rules.isEmpty()) throw err(head.no, type + " node '" + name + "' has no rules");
                try {
                    return switch (type) {
                        case "one" -> new OneNode(name, rules, steps);
                        case "all" -> new AllNode(name, rules, steps);
                        default -> new ParallelNode(name, rules, steps);
                    };
                } catch (RuleValidationException e) {
                    throw err(head.no, e.getMessage());
                }
            }
            default -> throw err(head.no, "unknown node type '" + type + "' (use one, all, prl, sequence, markov)");
        }
    }

    private static final java.util.regex.Pattern RULE_LINE =
        java.util.regex.Pattern.compile("^rule\\s+(\\S+)\\s+\"([^\"]*)\"\\s*->\\s*\"([^\"]*)\"(.*)$");

    private static Rule parseRule(Line line, Symmetry defaultSym, Legend legend) {
        var m = RULE_LINE.matcher(line.text);
        if (!m.matches()) throw err(line.no, "expected: rule <id> \"<in>\" -> \"<out>\" [p=X] [sym=S]");
        String id = m.group(1);
        double p = 1.0;
        Symmetry sym = defaultSym;
        for (String opt : m.group(4).trim().split("\\s+")) {
            if (opt.isEmpty()) continue;
            if (opt.startsWith("p=")) {
                try {
                    p = Double.parseDouble(opt.substring(2));
                } catch (NumberFormatException e) {
                    throw err(line.no, "rule '" + id + "': p must be a number");
                }
            } else if (opt.startsWith("sym=")) {
                try {
                    sym = Symmetry.parse(opt.substring(4));
                } catch (RuleValidationException e) {
                    throw err(line.no, e.getMessage());
                }
            } else throw err(line.no, "rule '" + id + "': unknown option '" + opt + "'");
        }
        try {
            return new Rule(id, line.comment, m.group(2), m.group(3), p, sym, legend);
        } catch (RuleValidationException e) {
            throw err(line.no, e.getMessage());
        }
    }

    /** Counts rules in a parsed tree (for reporting). */
    public static List<Rule> rulesOf(Node n) {
        List<Rule> out = new ArrayList<>();
        collect(n, out);
        return out;
    }

    private static void collect(Node n, List<Rule> out) {
        if (n instanceof RuleNode rn) out.addAll(rn.rules);
        else if (n instanceof SequenceNode s) s.children().forEach(c -> collect(c, out));
        else if (n instanceof MarkovNode m) m.children().forEach(c -> collect(c, out));
    }
}
