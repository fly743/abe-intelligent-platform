package com.abe.codegen.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 受保护密钥文件的有界读写；目录及访问权限由部署方管理。 */
public final class BswProtectedKeyFiles {
    private static final int MAX_FILE = 256 * 1024 + 256;
    private static final int MAGIC = 0x41424b50;
    private BswProtectedKeyFiles() { }

    /** 仅新建文件，不覆盖现有文件；写入失败时可能留下不完整文件。 */
    public static void saveNew(Path path, byte[] protectedKey) throws IOException {
        if (protectedKey == null || protectedKey.length < 4 || protectedKey.length > MAX_FILE
                || ByteBuffer.wrap(protectedKey).getInt() != MAGIC) throw new IllegalArgumentException("不是受保护密钥数据");
        Files.write(path, protectedKey, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public static byte[] read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] data = in.readNBytes(MAX_FILE + 1);
            if (data.length > MAX_FILE) throw new IOException("密钥文件超出大小限制");
            return data;
        }
    }
}
