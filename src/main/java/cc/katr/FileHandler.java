package cc.katr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扁平化、无 AOP 拦截的纯控制流业务处理类
 */
public class FileHandler {

    private final LocalFileStorage storage;
    // 基于内存的元数据数据库，用来应对短链接和对应实体的映射。启动时会从磁盘恢复。
    private final Map<String, FileRecord> metaDb = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public FileHandler(LocalFileStorage storage) {
        this.storage = storage;
        // 启动时从磁盘上恢复所有元数据记录
        try {
            List<Path> metadataPaths = storage.listMetadataPaths();
            for (Path path : metadataPaths) {
                try {
                    FileRecord record = mapper.readValue(path.toFile(), FileRecord.class);
                    if (record != null && record.shortCode() != null) {
                        metaDb.put(record.shortCode(), record);
                    }
                } catch (Exception e) {
                    System.err.println("⚠️ Warning: Failed to load metadata from " + path.getFileName() + ": " + e.getMessage());
                }
            }
            if (!metaDb.isEmpty()) {
                System.out.println("📦 Recovered " + metaDb.size() + " file metadata records from disk.");
            }
        } catch (Exception e) {
            System.err.println("💥 Error: Failed to list metadata paths on startup: " + e.getMessage());
        }
    }

    /**
     * 处理文件上传
     * endpoint: POST /api/files
     */
    public void uploadFile(Context ctx) {
        UploadedFile file = ctx.uploadedFile("file");
        
        // 显式校验边界，直接返回，没有全局切面异常拦截
        if (file == null) {
            ctx.status(400).json(Map.of("error", "No file uploaded"));
            return;
        }

        if (file.size() == 0) {
            ctx.status(400).json(Map.of("error", "Empty file is not allowed"));
            return;
        }

        String shortCode = ShortLinkGenerator.generate();
        String originalName = file.filename();
        String mimeType = file.contentType();
        long size = file.size();

        // 提取扩展名并进行拼接保证底层存储文件的可读性
        String ext = "";
        int dotIdx = originalName.lastIndexOf(".");
        if (dotIdx > 0 && dotIdx < originalName.length() - 1) {
            ext = originalName.substring(dotIdx);
        }
        String storedFileName = shortCode + ext;

        try (InputStream contentStream = file.content()) {
            // 这里 content() 即 InputStream，交由 LocalFileStorage 落地，使用 try-with-resources 确保流被关闭
            storage.save(storedFileName, contentStream);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to save file: " + e.getMessage()));
            return;
        }

        FileRecord record = new FileRecord(
            storedFileName,
            shortCode,
            originalName,
            size,
            mimeType,
            System.currentTimeMillis()
        );

        // 内存中保存
        metaDb.put(shortCode, record);

        // 持久化到磁盘（使用 shortCode.json 命名）
        try {
            mapper.writeValue(storage.getPath(shortCode + ".json").toFile(), record);
        } catch (Exception e) {
            System.err.println("⚠️ Warning: Failed to persist metadata for " + shortCode + ": " + e.getMessage());
            // 如果持久化失败，不中断上传，但记录一条警告
        }

        String downloadUrl = ctx.scheme() + "://" + ctx.host() + "/f/" + shortCode;
        
        // 成功，返回对应的 JSON 信息
        ctx.status(200).json(Map.of(
            "id", storedFileName,
            "shortCode", shortCode,
            "url", downloadUrl,
            "record", record
        ));
    }

    /**
     * 根据短链代码获取文件信息的接口
     * endpoint: GET /api/files/{shortCode}
     */
    public void getMetadata(Context ctx) {
        String shortCode = ctx.pathParam("shortCode");
        FileRecord record = metaDb.get(shortCode);
        if (record == null) {
            ctx.status(404).json(Map.of("error", "File not found"));
            return;
        }
        ctx.status(200).json(record);
    }

    /**
     * 根据短链接直接流式下载/请求文件资源
     * endpoint: GET /f/{shortCode}
     */
    public void downloadFile(Context ctx) {
        String shortCode = ctx.pathParam("shortCode");
        FileRecord record = metaDb.get(shortCode);
        if (record == null) {
            ctx.status(404).json(Map.of("error", "File not found"));
            return;
        }

        try {
            InputStream in = storage.read(record.id());
            
            // 写入硬设定的缓存配置
            ctx.header("Cache-Control", "public, max-age=31536000"); // 1 year 强缓存
            ctx.header("Content-Disposition", "inline; filename=\"" + record.originalName() + "\"");
            ctx.contentType(record.mimeType());
            
            // result() 接收 InputStream 时会自动异步分块响应，最终帮助我们关闭 InputStream
            ctx.result(in);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to retrieve file stream"));
        }
    }

    /**
     * 获取所有已上传的文件列表信息（按时间由近到远排列，限制前 100 条）
     * endpoint: GET /api/files
     */
    public void listFiles(Context ctx) {
        var files = metaDb.values().stream()
                .sorted((f1, f2) -> Long.compare(f2.createdAt(), f1.createdAt())) // 倒序
                .limit(100)
                .toList();
        
        ctx.status(200).json(files);
    }

    /**
     * 删除文件，包含物理文件与元数据文件
     * endpoint: DELETE /api/files/{shortCode}
     */
    public void deleteFile(Context ctx) {
        String shortCode = ctx.pathParam("shortCode");
        FileRecord record = metaDb.remove(shortCode);
        
        if (record == null) {
            ctx.status(404).json(Map.of("error", "File not found"));
            return;
        }

        // 尝试删除实际文件
        boolean dataDeleted = storage.delete(record.id());
        // 尝试删除对应的元数据 JSON 文件
        boolean metaDeleted = storage.delete(shortCode + ".json");

        // 如果实际上在磁盘中没找到，但也从内存中移除了，依旧返回成功。
        // 因为对于调用者来说，它的目的是“不存在这个文件”，不在乎它事前是否已经不存在。
        ctx.status(200).json(Map.of(
            "message", "Deleted successfully",
            "shortCode", shortCode,
            "dataDeleted", dataDeleted,
            "metaDeleted", metaDeleted
        ));
    }
}
