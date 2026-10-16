package fr.cobbledollars.commandshops.admin.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.admin.AdminMessage;

/** Localized outcome and authoritative runtime data; contains no configuration document. */
public record AdminLiveEnvelope(boolean success, JsonObject state, AdminMessage message) {
    public String json() {
        JsonObject result = new JsonObject();
        result.addProperty("success", success);
        if (state != null) result.add("state", state);
        JsonObject text = new JsonObject();
        text.addProperty("key", message.key());
        text.addProperty("fallback", message.fallback());
        JsonArray arguments = new JsonArray();
        message.arguments().forEach(arguments::add);
        text.add("arguments", arguments);
        result.add("message", text);
        return result.toString();
    }

    public static AdminLiveEnvelope parse(String json) {
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        JsonObject text = result.getAsJsonObject("message");
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
        text.getAsJsonArray("arguments").forEach(argument -> arguments.add(argument.getAsString()));
        return new AdminLiveEnvelope(result.get("success").getAsBoolean(), result.has("state")
                ? result.getAsJsonObject("state") : null, new AdminMessage(text.get("key").getAsString(),
                text.get("fallback").getAsString(), arguments));
    }
}
