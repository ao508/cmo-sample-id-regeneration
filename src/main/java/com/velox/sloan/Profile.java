package com.velox.sloan;

import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;

public enum Profile {
    PROD("prod"),
    DEV("dev");

    private static final Map<String, Profile> nameToProfile = new HashMap<>();

    static {
        for (Profile enumValue : values()) {
            nameToProfile.put(enumValue.name, enumValue);
        }
    }

    private final String name;

    Profile(String name) {
        this.name = name;
    }

    public static Profile fromString(String name) {
        if (!nameToProfile.containsKey(name))
            throw new RuntimeException(format("Unsupported %s: %s", Profile.class.getName(), name));

        return nameToProfile.get(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
