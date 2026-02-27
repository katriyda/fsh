package cc.katr;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扁平化、无 AOP 拦截的纯控制流业务处理类
 */
public class FileHandler {

    private final LocalFileStorage storage;
    // 简略的基于内存的元数据数据库，用来应对短链接和对应实体的映射。
    private final Map<String, FileRecord> metaDb = new ConcurrentHashMap<>();

    public FileHandler(LocalFileStorage storage) {
        this.storage = storage;
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

        metaDb.put(shortCode, record);

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
}
