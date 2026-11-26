package com.exkor.flipbot;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** `/flipbot set <key> <value>`: sets a public number, boolean or string field of a config object. Pure Java. */
public final class ConfigSetter {
    private ConfigSetter() {}

    /** Settable field names of the config, for tab completion and the error message. */
    public static List<String> keys(Object config) {
        List<String> keys = new ArrayList<>();
        for (Field f : config.getClass().getFields()) {
            if (settable(f)) keys.add(f.getName());
        }
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    /** Applies the value and returns the stored value as text; throws IllegalArgumentException with a readable reason. */
    public static String set(Object config, String key, String value) {
        Field field = null;
        for (Field f : config.getClass().getFields()) {
            if (f.getName().equalsIgnoreCase(key) && settable(f)) field = f;
        }
        if (field == null) throw new IllegalArgumentException("Unknown setting '" + key + "'");
        String v = value.trim();
        Class<?> t = field.getType();
        try {
            Object parsed;
            if (t == int.class) parsed = Integer.parseInt(v.replace("_", ""));
            else if (t == long.class) parsed = Long.parseLong(v.replace("_", ""));
            else if (t == double.class) parsed = Double.parseDouble(v.replace("_", ""));
            else if (t == boolean.class) parsed = parseBoolean(v);
            else parsed = v;
            field.set(config, parsed);
            return String.valueOf(field.get(config));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + v + "' is not a valid " + t.getSimpleName() + " for " + field.getName());
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Can't set " + field.getName());
        }
    }

    private static boolean parseBoolean(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> throw new NumberFormatException(v);
        };
    }

    private static boolean settable(Field f) {
        int m = f.getModifiers();
        Class<?> t = f.getType();
        return !Modifier.isStatic(m) && !Modifier.isFinal(m) && !f.getName().equals("configVersion")
                && (t == int.class || t == long.class || t == double.class || t == boolean.class || t == String.class);
    }
}
