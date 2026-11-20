package com.phoneconsole.pc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 外部命令（adb / ffmpeg）路径解析。
 * <p>
 * 配置里通常只写命令名（如 {@code "adb"}、{@code "ffmpeg"}），依赖 PATH 命中。
 * 为了让 app-image 在干净机器上也能工作，这里按顺序查找：
 * <ol>
 *   <li>配置值是绝对路径且存在</li>
 *   <li>PATH 中的同名可执行文件</li>
 *   <li>应用根目录下的相对子目录（如 {@code platform-tools\adb.exe}、{@code bin\ffmpeg.exe}）</li>
 *   <li>应用根目录的上一级（开发期 {@code pc-server} → 仓库根）</li>
 * </ol>
 * 都找不到时返回原值，由调用方以原有方式报错。
 */
public final class ToolPaths {

    private static final Logger log = LoggerFactory.getLogger(ToolPaths.class);

    private ToolPaths() {
    }

    public static String resolve(String configured, String... relativeDirs) {
        if (configured == null || configured.isBlank()) {
            return configured;
        }
        String value = configured.trim();
        Path direct = Path.of(value);
        if (direct.isAbsolute()) {
            return Files.exists(direct) ? direct.toString() : value;
        }

        List<String> names = new ArrayList<>();
        names.add(value);
        if (!value.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            names.add(value + ".exe");
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                for (String name : names) {
                    Path candidate = Path.of(dir, name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toString();
                    }
                }
            }
        }

        List<Path> bases = new ArrayList<>();
        Path home = AppPaths.home();
        bases.add(home);
        if (home.getParent() != null) {
            bases.add(home.getParent());
        }
        for (Path base : bases) {
            for (String dir : relativeDirs) {
                for (String name : names) {
                    Path candidate = base.resolve(dir).resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        log.info("外部命令已定位：{} -> {}", value, candidate);
                        return candidate.toString();
                    }
                }
            }
        }
        return value;
    }
}
