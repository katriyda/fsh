package cc.katr;

import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppIntegrationTest {

    private static Javalin app;
    private static int port;
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final String UPLOAD_DIR = "./data/fsh_uploads_test_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void setup() {
        LocalFileStorage storage = new LocalFileStorage(UPLOAD_DIR);
        FileHandler fileHandler = new FileHandler(storage);

        app = Javalin.create(config -> {
            config.useVirtualThreads = true;
            config.jsonMapper(new JavalinJackson());
        }).start(0);

        app.post("/api/files", fileHandler::uploadFile);
        app.get("/api/files/{shortCode}", fileHandler::getMetadata);
        app.get("/f/{shortCode}", fileHandler::downloadFile);

        port = app.port();
    }

    @AfterAll
    static void cleanup() throws Exception {
        app.stop();
        // 清理生成的测试临时文件及目录
        Files.walk(Paths.get(UPLOAD_DIR))
             .sorted(java.util.Comparator.reverseOrder())
             .forEach(path -> {
                 try { Files.delete(path); } catch (Exception ignored) {}
             });
    }

    @Test
    void testUploadAndDownloadFlow() throws Exception {
        String boundary = "TestBoundary" + System.currentTimeMillis();
        String fileContent = "hello virtual threads";
        
        // 极简手工构造 Multipart 请求体
        String body = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"test_go.txt\"\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                fileContent + "\r\n" +
                "--" + boundary + "--\r\n";

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/files"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> uploadResponse = client.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploadResponse.statusCode());
        
        String responseBody = uploadResponse.body();
        assertTrue(responseBody.contains("shortCode"));
        assertTrue(responseBody.contains("test_go.txt"));

        // 手工极简反序列化获取 shortCode
        String shortCode = responseBody.split("\"shortCode\":\"")[1].split("\"")[0];

        // 验证文件 Metadata (业务逻辑)
        HttpRequest metaRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/files/" + shortCode))
                .GET()
                .build();
        HttpResponse<String> metaResponse = client.send(metaRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metaResponse.statusCode());
        assertTrue(metaResponse.body().contains("test_go.txt"));

        // 验证文件完整流式下载
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/f/" + shortCode))
                .GET()
                .build();
        HttpResponse<String> downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, downloadResponse.statusCode());
        assertEquals(fileContent, downloadResponse.body());
        
        // 验证长缓存头拦截
        assertTrue(downloadResponse.headers().firstValue("Cache-Control").isPresent());
        assertEquals("public, max-age=31536000", downloadResponse.headers().firstValue("Cache-Control").get());
    }

    // 验证测试边界条件，空文件能否被正确地响应 400 Bad Request
    @Test
    void testEmptyFileUpload() throws Exception {
        String boundary = "TestBoundaryZero";
        String body = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"empty.txt\"\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                "\r\n" + // empty
                "--" + boundary + "--\r\n";

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/files"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> uploadResponse = client.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, uploadResponse.statusCode());
        assertTrue(uploadResponse.body().contains("Empty file is not allowed"));
    }
}
