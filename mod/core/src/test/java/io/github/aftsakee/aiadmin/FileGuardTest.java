package io.github.aftsakee.aiadmin;

import io.github.aftsakee.aiadmin.util.FileGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileGuardTest {
    @TempDir
    Path root;

    @Test
    void staysInsideRoot() {
        assertNull(FileGuard.resolve(root, "../secret.txt"));
        assertNull(FileGuard.resolve(root, "logs/../../x"));
        assertNull(FileGuard.resolve(root, root.getParent().toAbsolutePath().toString()));
        assertEquals("logs/latest.log", FileGuard.rel(root, FileGuard.resolve(root, "logs\\latest.log")));
        assertEquals(".", FileGuard.rel(root, FileGuard.resolve(root, "")));
    }

    @Test
    void globs() {
        List<String> deny = List.of("**/*.key", "world/playerdata/*.dat");
        assertTrue(FileGuard.denied("config/aiadmin/config.json", deny));
        assertTrue(FileGuard.denied("CONFIG/AIADMIN/prompts/x.md", deny));
        assertTrue(FileGuard.denied("config/aiadmin", deny));
        assertTrue(FileGuard.denied("a.key", deny));
        assertTrue(FileGuard.denied("x/y/z.key", deny));
        assertTrue(FileGuard.denied("world/playerdata/abc.dat", deny));
        assertFalse(FileGuard.denied("world/playerdata/sub/abc.dat", deny));
        assertFalse(FileGuard.denied("server.properties", deny));
        assertFalse(FileGuard.denied("config/aiadminx.json", deny));
    }

    /** 审查发现：Windows 打开文件时忽略段尾的点/空格，"config/aiadmin./config.json" 能读到密钥文件。 */
    @Test
    void checkedRejectsWindowsAliasTricks() throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("config/aiadmin"));
        java.nio.file.Files.writeString(root.resolve("config/aiadmin/config.json"), "{\"api_key\":\"SECRET\"}");
        java.nio.file.Files.createDirectories(root.resolve("logs"));
        java.nio.file.Files.writeString(root.resolve("logs/latest.log"), "ok");
        List<String> deny = List.of("**/*.key");
        for (String bad : List.of("config/aiadmin./config.json", "config./aiadmin/config.json", "config/aiadmin /config.json",
                "config/aiadmin/config.json::$DATA", "config/AIADMI~1/config.json", "x.key.", "CONFIG\\AIADMIN\\config.json",
                "../outside.txt", "logs/../config/aiadmin/config.json")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> FileGuard.checked(root, bad, deny), bad);
        }
        assertEquals(root.resolve("logs/latest.log").toAbsolutePath().normalize(), FileGuard.checked(root, "logs/latest.log", deny));
        assertEquals(root.toAbsolutePath().normalize(), FileGuard.checked(root, ".", deny));
    }

    @Test
    void boundedLineReading() throws Exception {
        List<String> got = new java.util.ArrayList<>();
        byte[] data = ("a\r\n" + "x".repeat(10_000) + "\nlast").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean cut = FileGuard.readLines(new java.io.ByteArrayInputStream(data), 100, 1 << 20, got::add);
        assertFalse(cut);
        assertEquals(3, got.size());
        assertEquals("a", got.get(0));
        assertTrue(got.get(1).length() < 200 && got.get(1).endsWith("已截断）"));
        assertEquals("last", got.get(2));
        got.clear();
        assertTrue(FileGuard.readLines(new java.io.ByteArrayInputStream(data), 100, 50, got::add)); // 只放行 50 字节
        got.clear();
        FileGuard.readLines(new java.io.ByteArrayInputStream(data), 100, 1 << 20, l -> got.add(l) && got.size() < 1); // 提前停止
        assertEquals(1, got.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsSecrets() {
        List<Object> r = FileGuard.redact(List.of("rcon.password=hunter2", "motd=hi", "  \"api_key\": \"sk-1\",", "management-server-secret=abc",
                "enable-rcon=false", "token="));
        List<String> out = (List<String>) r.get(0);
        assertEquals("rcon.password=[已隐藏]", out.get(0));
        assertEquals("motd=hi", out.get(1));
        assertEquals("  \"api_key\": [已隐藏]", out.get(2));
        assertEquals("management-server-secret=[已隐藏]", out.get(3));
        assertEquals("token=", out.get(5));
        assertEquals(3, r.get(1));
    }
}
