package io.reticulum;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public class TT {

    @Test
    void tt() throws IOException {
        var buffer = ByteBuffer.allocate(6)
                .putShort((short) 1)
                .putShort((short) 2)
                .putShort((short) 3);
        Files.deleteIfExists(Path.of("/tmp/123"));
        Files.write(Path.of("/tmp/123"), buffer.array(), StandardOpenOption.CREATE);
    }

    @Test
    void tr() throws IOException {
//        Files.isReadable(Path.of("/tmp/123"));
        var bytes = Files.readAllBytes(Path.of("/tmp/123"));
        System.out.println(Arrays.toString(bytes));
        var buffer = ByteBuffer.wrap(bytes);
        var one = buffer.getShort(0);
        var two = buffer.getShort(2);
        var three = buffer.getShort(4);
        System.out.printf("(%s)", String.join(", ", String.valueOf(one), String.valueOf(two), String.valueOf(three)));
    }
}
