package com.sms.query_carrier.controller;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/carrier")
public class QueryCarrierController {

    private static final Logger log = LoggerFactory.getLogger(QueryCarrierController.class);

    private static final Pattern pattern = Pattern.compile("This phone number is serviced by (.+?) Phone carriers");

    private static final  Map<String, String> carrierCache = new ConcurrentHashMap<>();

    // 1. 创建单线程的定时线程池
    private static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static String  filePathStr = "/opt/carrier/carrier.csv";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .proxyAuthenticator((route, response) -> {
                // 这行等同于 curl -U kaer1024:kaer1024
                String credential = Credentials.basic("kaer1024", "kaer1024");
                return response.request().newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
            })
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    //处短信接收
    public static final ExecutorService executor = new ThreadPoolExecutor(
            5,
            5,
            5,
            TimeUnit.MINUTES,
            new ArrayBlockingQueue<Runnable>(100),
            new NamedThreadFactory("查询运营商-")
    );

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(
                () -> {
                    appendCarrierCacheToCsv(true);
                },
                10,              // 初始延迟（0 表示立即执行）
                30,             // 间隔时间
                TimeUnit.MINUTES
        );
    }

    public static void appendCarrierCacheToCsv(boolean auto) {
        try {
            Path filePath = Paths.get(filePathStr);

            // 确保父目录存在
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            log.info("before cache:{}", QueryCarrierController.carrierCache.size());
            if (QueryCarrierController.carrierCache.isEmpty()) {
                return;
            }
            if (auto && QueryCarrierController.carrierCache.size() < 10000) {
                log.info("before cache:{} auto return", QueryCarrierController.carrierCache.size());
                return;
            }
            int count = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(
                    filePath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {

                for (Map.Entry<String, String> entry : QueryCarrierController.carrierCache.entrySet()) {
                    writer.write(escapeCsv(entry.getKey()));
                    writer.write(",");
                    writer.write(escapeCsv(entry.getValue()));
                    writer.newLine();
                    count ++;
                }
            }
            log.info("afer count:{}", count);
            QueryCarrierController.carrierCache.clear();
        } catch (Exception e) {
            log.error("appendCarrierCacheToCsv error", e);
        }
    }

    /**
     * 获取缓存信息
     * @param phone
     * @return
     */
    @GetMapping("/getCache")
    public Map<String, Object> getCache(String phone) {
        Map<String, Object> resultMap = new HashMap<>();
        int size = QueryCarrierController.carrierCache.size();
        resultMap.put("size", size);
        if (phone != null && !phone.isEmpty()) {
            String afterPhone = normalizePhone(phone);
            String carrier = QueryCarrierController.carrierCache.get(afterPhone);
            resultMap.put("carrier", carrier);
        }
        return resultMap;

    }

    /**
     * 获取缓存信息
     * @param phone
     * @return
     */
    @GetMapping("/appendCarrierCacheToCsv")
    public String appendCarrierCacheToCsv(String phone) {
        QueryCarrierController.appendCarrierCacheToCsv(false);
        return "ok";
    }



    /**
     * 查询运营商
     * @param phones
     * @return
     */
    @GetMapping("/query")
    public Map<String, List<String>> query(@RequestParam List<String> phones) {
        Map<String, List<String>> carrierMap = new ConcurrentHashMap<>();
        if (phones == null || phones.size() > 100 || phones.isEmpty()) {
            carrierMap.put("error", Collections.singletonList("号码不能为空并且不能超过100个"));
            return carrierMap;
        }
        List<Future<?>> futures = new ArrayList<>();
        long t1 = System.currentTimeMillis();
        synchronized (QueryCarrierController.class) {
            // 检查线程池队列剩余容量
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
            int remainingCapacity = tpe.getQueue().remainingCapacity();
            if (remainingCapacity < 100) {
                log.info("query 查询运营商服务器繁忙:{} 任务队列剩余容量:{}", phones.size(), remainingCapacity);
                carrierMap.put("error", Collections.singletonList("查询运营商服务器繁忙，请稍后再试"));
                return carrierMap;
            }
            log.info("query start:{}", phones.size());
            for (String phone : phones) {
                String afterPhone = normalizePhone(phone);
                try {
                    // 异步提交任务
                    Future<?> future = executor.submit(() -> getCarrier2(phone, afterPhone, carrierMap));
                    futures.add(future);
                } catch (RejectedExecutionException e) {
                    carrierMap.put("error", Collections.singletonList("查询运营商服务器繁忙，请稍后再试哦"));
                    log.warn("线程池满，拒绝任务: {}", phone);
                    return carrierMap;
                }
            }
        }
        // 等待所有异步任务完成
        for (Future<?> f : futures) {
            try {
                f.get(); // 可加 timeout 防止单个任务阻塞
            } catch (Exception e) {
                log.error("任务执行异常", e);
            }
        }
        log.info("query end:{}, time:{}ms", phones.size(), System.currentTimeMillis() - t1);
        return carrierMap;
    }



    /**
     * 规范化电话号码，将以下格式：
     *  "19704128976", "+19704128976", "0019704128976"
     *  统一转换为 "9704128976"
     */
    public static String normalizePhone(String phone) {
        if (phone == null || phone.isEmpty()) return phone;

        // 去掉所有非数字字符（保留数字）
        String digits = phone.replaceAll("\\D", "");

        // 如果长度大于 10，取最后 10 位
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }

        return digits;
    }

    public static void getCarrier2(String originPhone, String phoneNumber, Map<String, List<String>> carrierMap) {
        String carr = QueryCarrierController.carrierCache.get(phoneNumber);
        if (carr != null) {
            carrierMap.computeIfAbsent(carr, k -> new ArrayList<>()).add(originPhone);
            log.warn("originPhone cache:{} carr:{}", originPhone,carr);
            return;
        }

        // 2. 动态设置代理
        ProxyNode proxyNode = ProxyPool.next();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyNode.getHost(), proxyNode.getPort()));
        OkHttpClient clientWithProxy = client.newBuilder().proxy(proxy).build();

        Request request = new Request.Builder()
                .url("https://www.anywho.com/phone/" + phoneNumber)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36...")
                .build();

        try (Response response = clientWithProxy.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                // 3. 将 OkHttp 的响应体交给 Jsoup 解析
                Document doc = Jsoup.parse(response.body().string());
                log.info("originPhone:{} proxy:{}, port:{}", originPhone, proxyNode.getHost(), proxyNode.getPort());
                // 方法1: 根据关键字搜索文本 "This phone number is serviced by"
                String bodyText = doc.body().text();
                int idx = bodyText.indexOf("This phone number is serviced by");
                if (idx != -1) {
                    Matcher matcher = pattern.matcher(bodyText);
                    if (matcher.find()) {
                        String carrier = matcher.group(1).trim();
                        if (carrier.endsWith(".")) {
                            carrier = carrier.substring(0, carrier.length() - 1);
                        }
                        carrierMap.computeIfAbsent(carrier, k -> new ArrayList<>()).add(originPhone);
                        QueryCarrierController.carrierCache.put(phoneNumber, carrier);
                    }
                } else {
                    log.info("未知号码(可能属于加拿大？):{}", originPhone);
                    carrierMap.computeIfAbsent("未知号码(可能属于加拿大？)", k -> new ArrayList<>()).add(originPhone);
                }
                // ... 你的解析逻辑 ...
            }
        } catch (IOException e) {
            log.error("请求失败:{}", phoneNumber, e);
            carrierMap.computeIfAbsent("查询失败的号码", k -> new ArrayList<>()).add(originPhone);
        }
    }

    /*public static void getCarrier(String originPhone, String phoneNumber, Map<String, List<String>> carrierMap) {
        String url = "https://www.anywho.com/phone/" + phoneNumber;
        try {
            if (StringUtils.isEmpty(phoneNumber)) {
                return;
            }
            if (phoneNumber.length() != 10) {
                carrierMap.computeIfAbsent("未知", k -> new ArrayList<>()).add(originPhone);
                return ;
            }
            String carr = QueryCarrierController.carrierCache.get(phoneNumber);
            if (carr != null) {
                carrierMap.computeIfAbsent(carr, k -> new ArrayList<>()).add(originPhone);
                log.warn("originPhone cache:{} carr:{}", originPhone,carr);
                return;
            }
            if (QueryCarrierController.carrierCache.size() > 150000) {
                QueryCarrierController.carrierCache.clear();
            }
            ProxyNode proxy = ProxyPool.next();
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0") // 模拟浏览器，防止被拦截
                    //.proxy(proxy.getHost(), proxy.getPort())
                    .timeout(5000)
                    .get();
            log.info("originPhone:{} proxy:{}, port:{}", originPhone, proxy.getHost(), proxy.getPort());
            // 方法1: 根据关键字搜索文本 "This phone number is serviced by"
            String bodyText = doc.body().text();
            int idx = bodyText.indexOf("This phone number is serviced by");
            if (idx != -1) {
                Matcher matcher = pattern.matcher(bodyText);
                if (matcher.find()) {
                    String carrier = matcher.group(1).trim();
                    if (carrier.endsWith(".")) {
                        carrier = carrier.substring(0, carrier.length() - 1);
                    }
                    carrierMap.computeIfAbsent(carrier, k -> new ArrayList<>()).add(originPhone);
                    QueryCarrierController.carrierCache.put(phoneNumber, carrier);
                }
            }
        } catch (IOException e) {
            log.error("请求失败:{}", phoneNumber, e);
            carrierMap.computeIfAbsent("未知", k -> new ArrayList<>()).add(originPhone);
        }
    }*/


    /**
     * CSV 字段转义
     */
    private static String escapeCsv(String field) {
        if (field == null) {
            return "";
        }
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            field = field.replace("\"", "\"\"");
            return "\"" + field + "\"";
        }
        return field;
    }

}
