package cc.katr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;

/**
 * 外部化应用配置
 */
public record AppConfig(
    int port,
    String storagePath,
    long maxRequestSize
) {
    /**
     * 加载配置文件；如果不存在，则自动在该路径下生成具有默认值的配置文件。
     */
    public static AppConfig loadOrCreate(String path) {
        ObjectMapper mapper = new ObjectMapper();
        File configFile = new File(path);
        // 默认配置
        AppConfig defaultConfig = new AppConfig(8080, "./data/fsh_uploads", 2147483648L); // 最大请求默认 2GB
        
        try {
            if (configFile.exists()) {
                return mapper.readValue(configFile, AppConfig.class);
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
