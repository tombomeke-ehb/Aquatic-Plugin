package dev.aquatic.events.tournament;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/** One pairing in the bracket. b == null means a bye for a. */
public final class BracketMatch {

    public String a;
    public String b;
    public String winner;
    /** Game wins in a best-of-N finale. */
    public int winsA;
    public int winsB;

    public BracketMatch(String a, String b) {
        this.a = a;
        this.b = b;
    }

    public boolean isBye() {
        return b == null || a == null;
    }

    public boolean done() {
        return winner != null;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", a);
        m.put("b", b);
        m.put("winner", winner);
        m.put("winsA", winsA);
        m.put("winsB", winsB);
        return m;
    }

    public static BracketMatch deserialize(Map<?, ?> m) {
        BracketMatch bm = new BracketMatch(str(m.get("a")), str(m.get("b")));
        bm.winner = str(m.get("winner"));
        bm.winsA = num(m.get("winsA"));
        bm.winsB = num(m.get("winsB"));
        return bm;
    }

    public static BracketMatch load(ConfigurationSection s) {
        return deserialize(s.getValues(false));
    }

    private static String str(Object o) {
        return o == null || "null".equals(String.valueOf(o)) ? null : String.valueOf(o);
    }

    private static int num(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
