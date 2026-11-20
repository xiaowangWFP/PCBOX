package com.phoneconsole.pc;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用安装位置解析。
 * <p>
 * jpackage app-image 的布局是 {@code <home>\app\<jar>}，此时应用根目录为 {@code <home>}；
 * 开发期直接 {@code java -jar target\xxx.jar} 时目录名不是 app，退回当前工作目录，
 * 与原有行为一致。
 */
public final class AppPaths {

    private AppPaths() {
    }

    /** @return 应用根目录（app-image 为 exe 所在目录，开发期为工作目录） */
    public static Path home() {
        try {
            Path self = Path.of(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            if (dir != null && dir.getFileName() != null && "app".equals(dir.getFileName().toString())) {
                Path home = dir.getParent();
                if (home != null) {
                    return home;
                }
            }
        } catch (Exception ignored) {
            // 取不到位置时按工作目录处理
        }
        return Path.of("").toAbsolutePath();
    }
}
