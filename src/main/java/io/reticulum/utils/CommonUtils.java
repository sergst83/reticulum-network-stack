package io.reticulum.utils;

import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PRIVATE;

@NoArgsConstructor(access = PRIVATE)
public final class CommonUtils {

    public static void panic() {
        System.exit(255);
    }

    public static void exit() {
        System.exit(0);
    }
}
