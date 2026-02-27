package cc.katr;

import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.plugin.bundled.CorsPluginConfig;

public class Main {
    static void main() {
        // 在启动时读取或生成默认外部配置文件
        AppConfig config = AppConfig.loadOrCreate("config.json");
        System.out.println("⚙️ Loaded configuration: Port=" + config.port() + " | StoragePath=" + config.storagePath() + " | MaxUploadSize=" + config.maxRequestSize() + " bytes");

        // 创建扁平化依赖资源
        LocalFileStorage storage = new LocalFileStorage(config.storagePath());
        FileHandler fileHandler = new FileHandler(storage);

        // 解析和标准化 adminPathPrefix
        String prefix = config.adminPathPrefix() != null ? config.adminPathPrefix().trim() : "";
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        final String finalPrefix = prefix;

        // 创建基于虚拟线程的高并发 Http 服务
        Javalin app = Javalin.create(javalinConfig -> {
            // 开启 Java 21+ Virtual Threads，所有请求并发将完全脱离系统线程池
            javalinConfig.useVirtualThreads = true;
            // 配置 Jackson 作为默认 JSON 解析器
            javalinConfig.jsonMapper(new JavalinJackson());
            
            // 声明静态文件托管（前端控制台放置于 src/main/resources/public 下），挂载到前缀路径
            javalinConfig.staticFiles.add(staticFileConfig -> {
                staticFileConfig.hostedPath = finalPrefix.isEmpty() ? "/" : finalPrefix;
                staticFileConfig.directory = "/public";
                staticFileConfig.location = io.javalin.http.staticfiles.Location.CLASSPATH;
            });

            // 声明极简跨域处理（Cors）：允许任意来源跨域请求
            javalinConfig.bundledPlugins.enableCors(cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));
            
            // 可配置超大文件支持：修改默认最大请求体 (来源于配置文件)
            javalinConfig.http.maxRequestSize = config.maxRequestSize(); 
        }).start(config.port()); // 从配置文件中读取启动端口

        // 注册业务 API 路由 (管理页面 API 加上前缀)
        app.get(finalPrefix + "/api/files", fileHandler::listFiles); // 获取列表接口
        app.post(finalPrefix + "/api/files", fileHandler::uploadFile);
        app.get(finalPrefix + "/api/files/{shortCode}", fileHandler::getMetadata);
        app.delete(finalPrefix + "/api/files/{shortCode}", fileHandler::deleteFile);
        
        // 下载路由始终在根路径，不受前缀影响
        app.get("/f/{shortCode}", fileHandler::downloadFile);
        
        System.out.println("🚀 [fsh] application is running on http://localhost:" + config.port() + finalPrefix + ". Empowered by Virtual Threads.");
    }
}
