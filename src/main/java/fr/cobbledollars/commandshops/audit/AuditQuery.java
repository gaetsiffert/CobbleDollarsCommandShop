package fr.cobbledollars.commandshops.audit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/** Explicit bounded read-only query. Filters refer to recorded identifiers, including deleted shops. */
public record AuditQuery(String view, String window, String search, String shop, String player,
                         String item, String offer, String event, String sort, int page, String cursor) {
    public static final int PAGE_SIZE = 25;
    private static final Set<String> VIEWS = Set.of("summary", "shops", "players", "items", "offers", "journal");
    private static final Set<String> SORTS = Set.of("volume", "count", "name", "recent");

    public AuditQuery {
        if (!VIEWS.contains(view) || AuditTimeWindow.fromArgument(window) == null || !SORTS.contains(sort))
            throw new IllegalArgumentException("Invalid audit view, period or sort order.");
        if (!"all".equals(event) && AuditEventType.fromEventName(event) == null)
            throw new IllegalArgumentException("Invalid audit event type.");
        for (String value : new String[]{search, shop, player, item, offer}) {
            if (value == null || value.length() > 256) throw new IllegalArgumentException("Audit filter is too long.");
        }
        if (cursor == null || cursor.length() > 512 || page < 0 || page > 100_000)
            throw new IllegalArgumentException("Invalid audit page.");
    }

    public static AuditQuery parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return new AuditQuery(value(o,"view","summary"), value(o,"window","all"), value(o,"search",""),
                value(o,"shop",""), value(o,"player",""), value(o,"item",""), value(o,"offer",""),
                value(o,"event","all"), value(o,"sort","volume"), o.has("page") ? o.get("page").getAsBigDecimal().intValueExact() : 0,
                value(o,"cursor",""));
    }

    public String json() { return new Gson().toJson(this); }
    public AuditTimeWindow timeWindow() { return AuditTimeWindow.fromArgument(window); }
    String filterKey() { return new Gson().toJson(new String[]{window,shop,player,item,offer,event}); }
    private static String value(JsonObject o, String key, String fallback) { return o.has(key) ? o.get(key).getAsString() : fallback; }
}
