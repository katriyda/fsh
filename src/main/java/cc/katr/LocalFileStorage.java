package cc.katr;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * 结构扁平化、无框架依赖的本地文件存储封装
 */
public class LocalFileStorage {
    private final Path baseDir;

    public LocalFileStorage(String dirPath) {
        this.baseDir = Paths.get(dirPath).toAbsolutePath().normalize();
        try {
            if (!Files.exists(this.baseDir)) {
                Files.createDirectories(this.baseDir);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize storage directory: " + dirPath, e);
        }
    }

    /**
     * 将输入流保存为本地文件。
     * 直接使用底层 NIO 的能力，避免在内存中缓存完整文件。
     */
    public void save(String fileName, InputStream in) throws Exception {
        Path targetPath = resolveAndCheckPath(fileName);
        Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 获取指定文件的安全且已校验路径
     */
    public Path getPath(String fileName) {
        return resolveAndCheckPath(fileName);
    }
    
    /**
     * 历遍获取所有 metadata (.json) 文件的路径
     */
    public List<Path> listMetadataPaths() throws Exception {
        try (Stream<Path> stream = Files.list(baseDir)) {
            return stream
                    .filter(path -> !Files.isDirectory(path))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
        }
    }

    /**
     * 读取指定文件为 InputStream
     */
    public InputStream read(String fileName) throws Exception {
        return Files.newInputStream(resolveAndCheckPath(fileName));
    }

    /**
     * 安全删除指定文件
     *
     * @return 如果文件被删除则返回 true，如果不存在则返回 false
     */
    public boolean delete(String fileName) {
        try {
            Path targetPath = resolveAndCheckPath(fileName);
            return Files.deleteIfExists(targetPath);
        } catch (Exception e) {
            System.err.println("Failed to delete file '" + fileName + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * 进行基础的路径遍历漏洞防护
     */
    private Path resolveAndCheckPath(String fileName) {
        Path targetPath = baseDir.resolve(fileName).normalize();
        if (!targetPath.startsWith(baseDir)) {
            throw new SecurityException("Invalid target file path: " + fileName);
        }
        return targetPath;
    }
}
