package com.example.slidercaptcha.utils;

import java.util.HashMap;
import java.util.Map;

public class ThreadLocalContext {
    private static final ThreadLocal<Map<String, Object>> THREAD_LOCAL = new ThreadLocal<>();

    public static Map<String, Object> get() {
        return THREAD_LOCAL.get();
    }

    public static <T> T get(String key) {
        Map<String, Object> map = THREAD_LOCAL.get();
        if (map == null) {
            return null;
        }
        return (T) map.get(key);
    }

    public static void set(String key, Object value) {
        Map<String, Object> map = THREAD_LOCAL.get();
        if (map == null) {
            map = new HashMap<>();
            THREAD_LOCAL.set(map);
        }
        map.put(key, value);
    }

    public static void clear() {
        Map<String, Object> map = THREAD_LOCAL.get();
        if (map != null) {
            map.clear();
        }
        THREAD_LOCAL.remove();
    }

    public static <T> T remove(String key) {
        Map<String, Object> map = THREAD_LOCAL.get();
        if (map != null) {
            return (T) map.remove(key);
        }
        return null;
    }
}
