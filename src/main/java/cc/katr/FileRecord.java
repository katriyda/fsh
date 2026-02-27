package cc.katr;

/**
 * 极简的文件数据实体，使用 Java Record 保证不变性且减少冗余的 Getter/Setter 样板代码
 */
public record FileRecord(
    String id,
    String shortCode,
    String originalName,
    long size,
    String mimeType,
    long createdAt
) {}
