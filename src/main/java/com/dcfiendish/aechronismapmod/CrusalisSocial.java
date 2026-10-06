package com.dcfiendish.aechronismapmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converts the map site's /api-proxy/v1/map/social response into the old nodes/towns.json
 * shape that {@link AechronisMapData#loadTownsData} reads, the same join the site's own
 * bundle does.
 *
 * social is flat arrays keyed by UUID:
 *   nations   {uuid, name, capital_town_uuid, color_r/g/b}
 *   towns     {uuid, name, nation_uuid, leader_uuid, home_territory_id, spawn_x/y/z, color_r/g/b, is_open}
 *   territories {town_uuid, territory_id, role:"core"|"territory"} (home appears once per role)
 *   members   {town_uuid, resident_uuid, is_officer, is_general}
 *   residents {uuid, username}
 *   nameOverrides [{territory_id, name}]
 * and becomes
 *   nations   {name: {capital: townName, color:[r,g,b], towns:[townName...]}}
 *   towns     {name: {home, spawn:[x,y,z], color:[r,g,b], territories:[id...], residents:[uuid...]}}
 *   residents {uuid: {name}}
 * The social feed has no captured/annexed lists or allies/enemies, so those stay absent.
 */
public final class CrusalisSocial {
    private CrusalisSocial() {}

    public static JsonObject toLegacyTowns(JsonObject social) {
        Map<String, String> townNameByUuid = new HashMap<>();
        Map<String, JsonObject> townByUuid = new HashMap<>();
        Map<String, Set<String>> townTerritories = new HashMap<>();
        Map<String, JsonArray> townResidents = new HashMap<>();
        JsonObject towns = new JsonObject();

        for (JsonObject t : objects(social, "towns")) {
            String uuid = str(t, "uuid");
            String name = str(t, "name");
            if (uuid == null || name == null) continue;
            JsonObject town = new JsonObject();
            town.addProperty("uuid", uuid);
            String home = str(t, "home_territory_id");
            if (home != null) town.addProperty("home", home);
            if (has(t, "spawn_x") && has(t, "spawn_y") && has(t, "spawn_z")) {
                JsonArray spawn = new JsonArray();
                spawn.add(t.get("spawn_x"));
                spawn.add(t.get("spawn_y"));
                spawn.add(t.get("spawn_z"));
                town.add("spawn", spawn);
            }
            JsonArray color = color(t);
            if (color != null) town.add("color", color);
            String leader = str(t, "leader_uuid");
            if (leader != null) town.addProperty("leader", leader);
            if (has(t, "is_open")) town.add("open", t.get("is_open"));
            townNameByUuid.put(uuid, name);
            townByUuid.put(uuid, town);
            townTerritories.put(uuid, new LinkedHashSet<>());
            townResidents.put(uuid, new JsonArray());
            towns.add(name, town);
        }

        // Deduped: the home territory is listed once as "core" and once as "territory".
        for (JsonObject row : objects(social, "territories")) {
            Set<String> ids = townTerritories.get(str(row, "town_uuid"));
            String tid = str(row, "territory_id");
            if (ids != null && tid != null) ids.add(tid);
        }
        for (JsonObject row : objects(social, "members")) {
            JsonArray residents = townResidents.get(str(row, "town_uuid"));
            String resident = str(row, "resident_uuid");
            if (residents != null && resident != null) residents.add(resident);
        }
        for (Map.Entry<String, JsonObject> e : townByUuid.entrySet()) {
            JsonArray ids = new JsonArray();
            townTerritories.get(e.getKey()).forEach(ids::add);
            e.getValue().add("territories", ids);
            e.getValue().add("residents", townResidents.get(e.getKey()));
        }

        Map<String, JsonArray> nationTowns = new HashMap<>();
        for (JsonObject t : objects(social, "towns")) {
            String nationUuid = str(t, "nation_uuid");
            String name = townNameByUuid.get(str(t, "uuid"));
            if (nationUuid != null && name != null) {
                nationTowns.computeIfAbsent(nationUuid, k -> new JsonArray()).add(name);
            }
        }
        JsonObject nations = new JsonObject();
        for (JsonObject n : objects(social, "nations")) {
            String uuid = str(n, "uuid");
            String name = str(n, "name");
            if (uuid == null || name == null) continue;
            JsonObject nation = new JsonObject();
            String capital = townNameByUuid.get(str(n, "capital_town_uuid"));
            if (capital != null) nation.addProperty("capital", capital);
            JsonArray color = color(n);
            if (color != null) nation.add("color", color);
            nation.add("towns", nationTowns.getOrDefault(uuid, new JsonArray()));
            nations.add(name, nation);
        }

        JsonObject residents = new JsonObject();
        for (JsonObject r : objects(social, "residents")) {
            String uuid = str(r, "uuid");
            String username = str(r, "username");
            if (uuid == null || username == null) continue;
            JsonObject resident = new JsonObject();
            resident.addProperty("name", username);
            residents.add(uuid, resident);
        }

        JsonObject out = new JsonObject();
        out.add("nations", nations);
        out.add("towns", towns);
        out.add("residents", residents);
        return out;
    }

    /** Applies social.nameOverrides to geometry.json's territory names, in place. */
    public static void applyNameOverrides(JsonObject geometry, JsonObject social) {
        if (!geometry.has("territories") || !geometry.get("territories").isJsonObject()) return;
        JsonObject territories = geometry.getAsJsonObject("territories");
        for (JsonObject o : objects(social, "nameOverrides")) {
            String tid = str(o, "territory_id");
            String name = str(o, "name");
            JsonElement t = tid == null ? null : territories.get(tid);
            if (name != null && t != null && t.isJsonObject()) t.getAsJsonObject().addProperty("name", name);
        }
    }

    private static Iterable<JsonObject> objects(JsonObject parent, String key) {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();
        JsonElement el = parent.get(key);
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement e : el.getAsJsonArray()) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    private static boolean has(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }

    /** Ids arrive as numbers (territory_id) or strings (uuids); both become strings. */
    private static String str(JsonObject o, String key) {
        if (!has(o, key)) return null;
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            if (d == Math.rint(d)) return Long.toString((long) d);
        }
        return e.getAsString();
    }

    private static JsonArray color(JsonObject o) {
        if (!has(o, "color_r") || !has(o, "color_g") || !has(o, "color_b")) return null;
        JsonArray c = new JsonArray();
        c.add(o.get("color_r").getAsInt());
        c.add(o.get("color_g").getAsInt());
        c.add(o.get("color_b").getAsInt());
        return c;
    }
}
