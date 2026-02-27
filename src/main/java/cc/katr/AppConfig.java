package cc.katr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;

/**
 * 外部化应用配置
 */
public record AppConfig(
    int port,
    String storagePath,
    long maxRequestSize,
    String adminPathPrefix
) {
    /**
     * 加载配置文件；如果不存在，则自动在该路径下生成具有默认值的配置文件。
     */
    public static AppConfig loadOrCreate(String path) {
        ObjectMapper mapper = new ObjectMapper();
        File configFile = new File(path);
        // 默认配置 (默认不开启前缀，即根路径)
        AppConfig defaultConfig = new AppConfig(8080, "./data/fsh_uploads", 2147483648L, ""); 
        
        try {
            if (configFile.exists()) {
                AppConfig loaded = mapper.readValue(configFile, AppConfig.class);
                // 兼容旧版本配置文件，如果前缀为 null 则修正为 ""
                if (loaded.adminPathPrefix() == null) {
                    return new AppConfig(loaded.port(), loaded.storagePath(), loaded.maxRequestSize(), "");
                }
                return loaded;
            } else {
                // 首次运行或配置文件不存在时，自动导出默认配置以供修改
                mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, defaultConfig);
                System.out.println("📝 Configuration file created at: " + configFile.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("⚠️ Warning: failed to read/write config file '" + path + "'. Using defaults. Error: " + e.getMessage());
        }
        
        return defaultConfig;
    }
}
