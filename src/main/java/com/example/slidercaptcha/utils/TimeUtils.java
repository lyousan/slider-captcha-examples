package com.example.slidercaptcha.utils;

import lombok.SneakyThrows;

import java.util.Random;

public class TimeUtils {
    private static final Random RANDOM = new Random();

    /**
     * 延时
     *
     * @param second 秒
     */
    @SneakyThrows
    public static void sleep(int second) {
        Thread.sleep(second * 1000L);
    }

    /**
     * 延时
     *
     * @param min 最短时间
     * @param max 最长时间
     */
    public static void sleep(int min, int max) {
        sleep((RANDOM.nextInt(max + 1 - min) + min));
    }

}
