package com.example.slidercaptcha.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.microsoft.playwright.impl.Connection;
import com.microsoft.playwright.impl.PipeTransport;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;


public class PwUtils {
    private static final Logger log = LoggerFactory.getLogger(PwUtils.class);
    private static String STEALTH_PATH;
    // TLK: Thread Local Key
    public static final String PLAYWRIGHT_TLK = "PwUtils::Playwright";
    public static final String WAIT_FOR_LOAD_STATE_OPTIONS_TLK = "PwUtils::WaitForLoadStateOptions";

    static {
        loadStealthJs();
    }

    private static final CloseableHttpClient httpClient = HttpClients.createDefault();

    @Getter
    @Setter
    @AllArgsConstructor
    static public class BrowserWithProcess {
        Browser browser;
        Process process;
    }

    @SneakyThrows
    public static BrowserWithProcess createByCmd(String chromeExecutablePath, File userDataDir) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                chromeExecutablePath,
                "--remote-debugging-port=0",
                "--remote-debugging-address=0.0.0.0",
                "--remote-allow-origins=*",
                "--user-data-dir=" + userDataDir.getAbsolutePath());
        Process chromeProcess = processBuilder.start();
        File portFile = new File(userDataDir, "DevToolsActivePort");
        log.debug("读取chrome debugging port...");
        while (!portFile.exists()) {
            TimeUtils.sleep(1);
        }
        String port = FileUtils.readLines(portFile, StandardCharsets.UTF_8).stream().findFirst().orElseThrow(() -> new RuntimeException("读取chrome debugging port失败"));
        String url = "http://127.0.0.1:" + port + "/json/version";
        log.debug("chrome debugging url : {}", url);
        TimeUtils.sleep(1);
        HttpGet httpGet = new HttpGet(url);
        int retry = 3;
        while (retry-- > 0) {
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String result = EntityUtils.toString(response.getEntity());
                HashMap<String, String> versionInfo = new ObjectMapper().readValue(result, HashMap.class);
                String wsUrl = versionInfo.get("webSocketDebuggerUrl");
                return new BrowserWithProcess(PwUtils.getPlaywright().chromium().connectOverCDP(wsUrl), chromeProcess);
            } catch (Exception e) {
                log.debug("获取chrome debugging port失败，重试中...");
                TimeUtils.sleep(2);
            }
        }
        throw new RuntimeException("通过cdp创建浏览器连接失败");
    }

    private static final AtomicInteger id = new AtomicInteger(0);

    public static void maxWindow(Page page) {
        CDPSession session = page.context().newCDPSession(page);
        int windowId = session.send("Browser.getWindowForTarget").get("windowId").getAsInt();
        JsonObject params = new JsonObject();
        params.addProperty("id", id.addAndGet(1));
        params.addProperty("windowId", windowId);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("windowState", "maximized");
        params.add("bounds", bounds);
        session.send("Browser.setWindowBounds", params);
    }

    /**
     * 默认的playwright，会在当前线程中缓存，该类中有不少类似的做法，都是为了避免多次创建对象
     * 因为playwright对象每次创建都会有一个nodejs的进程，如果不调用close()关闭的话，长时间下来会引起很高的内存占用
     *
     * @return playwright实例，用于创建浏览器
     */
    private static Playwright defaultPlaywright() {
        Playwright.CreateOptions createOptions = new Playwright.CreateOptions();
        Map<String, String> env = new HashMap<>(1);
        // 不下载浏览器
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        createOptions.setEnv(env);
        return Playwright.create(createOptions);
    }

    private static Page.WaitForLoadStateOptions defaultWaitForLoadStateOptions() {
        Page.WaitForLoadStateOptions waitForLoadStateOptions = new Page.WaitForLoadStateOptions().setTimeout(3 * 60 * 1000);
        setWaitForLoadStateOptions(waitForLoadStateOptions);
        return waitForLoadStateOptions;
    }

    public static void setPlaywright(Playwright playwright) {
        ThreadLocalContext.set(PLAYWRIGHT_TLK, playwright);
    }

    /**
     * 获取当前线程中的playwright对象，如果没有则创建一个
     */
    public static Playwright getPlaywright() {
        Playwright playwright = ThreadLocalContext.get(PLAYWRIGHT_TLK);
        if (playwright == null) {
            playwright = defaultPlaywright();
            setPlaywright(playwright);
        }
        return playwright;
    }

    /**
     * 加载伪装js
     */
    private static void loadStealthJs() {
        File tmpFile = null;
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(ResourceUtils.CLASSPATH_URL_PREFIX + "stealth.min.js");
            File baseDir = new File(".");
            // 不要使用getFile，jar包运行时会出现错误，jar包内的文件不能直接作为系统路径的文件来使用，需要使用流来处理
//                File file = resource.getFile();
            String filename = resource.getFilename();
            tmpFile = ResourceUtils.getFile(baseDir + "/resources/" + filename);
            checkDir(tmpFile.getParentFile());
            if (tmpFile.exists()) {
                log.debug("{}[tmpFile] exist ==> {}", filename, tmpFile.getAbsolutePath());
            } else {
                log.debug("{}[tmpFile] copy ==> {}", filename, tmpFile.getAbsolutePath());
                Files.copy(resource.getInputStream(), Paths.get(tmpFile.toURI()));
            }
        } catch (Exception e) {
            log.error("加载伪装js时发生异常 ==> ", e);
        }
        STEALTH_PATH = Objects.requireNonNull(tmpFile).getAbsolutePath();
    }

    /**
     * 设置仅限当前线程内生效
     *
     * @param options
     */
    public static void setWaitForLoadStateOptions(Page.WaitForLoadStateOptions options) {
        ThreadLocalContext.set(WAIT_FOR_LOAD_STATE_OPTIONS_TLK, options);
    }

    public static Page.WaitForLoadStateOptions getWaitForLoadStateOptions() {
        Page.WaitForLoadStateOptions waitForLoadStateOptions = ThreadLocalContext.get(WAIT_FOR_LOAD_STATE_OPTIONS_TLK);
        if (waitForLoadStateOptions == null) {
            waitForLoadStateOptions = defaultWaitForLoadStateOptions();
        }
        return waitForLoadStateOptions;
    }

    public static Browser chromeBrowser(Playwright playwright, BrowserType.LaunchOptions launchOptions) {
        try {
            return playwright.chromium().launch(launchOptions);
        } catch (Exception e) {
            log.error("创建谷歌浏览器时发生异常 ==> ", e);
            return null;
        }
    }

    public static Browser chromeBrowser() {
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions();
        // 关闭无头模式
        launchOptions.setHeadless(false);
        // 以chrome标准版启动，本机安装了谷歌浏览器就可以不下载PlayWright的浏览器文件，可以共用Selenium所需要的谷歌浏览器
        launchOptions.setChannel("chrome");
        List<String> args = new ArrayList<>();
//        args.add("--start-fullscreen");
        // 窗口最大化，目前playwright还没有提供窗口的最大化/最下化/全屏等API
        args.add("--start-maximized");
        return chromeBrowser(getPlaywright(), launchOptions.setArgs(args));
    }

    public static Browser chromeBrowser(Proxy proxy) {
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions();
        // 关闭无头模式
        launchOptions.setHeadless(false);
        // 以chrome标准版启动，本机安装了谷歌浏览器就可以不下载PlayWright的浏览器文件，可以共用Selenium所需要的谷歌浏览器
        launchOptions.setChannel("chrome");
        // context想要配置的代理生效，browser就必须加上代理的启动配置，context可以覆盖browser的代理配置，但browser必须得有一个代理配置，
        // 如果所有context都覆盖了代理配置，那么browser的代理配置就不会被用到，可以是任意的字符串作为占位
        launchOptions.setProxy(proxy == null ? new Proxy("enable proxy") : proxy);
        List<String> args = new ArrayList<>();
//        args.add("--start-fullscreen");
        // 窗口最大化，目前playwright还没有提供窗口的最大化/最下化/全屏等API
        args.add("--start-maximized");
        return chromeBrowser(getPlaywright(), launchOptions.setArgs(args));
    }

    @SneakyThrows
    public static BrowserContext defaultBrowserContext(Browser browser, File storageFile) {
        checkFile(storageFile);
        BrowserContext browserContext = browser.newContext(new Browser.NewContextOptions().setViewportSize(null).setStrictSelectors(false).setStorageStatePath(storageFile.toPath()));
        browserContext.addInitScript(new File(STEALTH_PATH).toPath());
        browserContext.setDefaultTimeout(5 * 1000);
        browserContext.setDefaultNavigationTimeout(60 * 3 * 1000);
        return browserContext;
    }

    public static BrowserContext browserContext(Browser browser) {
        return browser.newContext(new Browser.NewContextOptions().setViewportSize(null).setStrictSelectors(false));
    }

    public static BrowserContext browserContext(Browser browser, BrowserContext.StorageStateOptions storageStateOptions) {
        return browser.newContext(new Browser.NewContextOptions().setViewportSize(null).setStrictSelectors(false).setStorageStatePath(storageStateOptions.path));
    }


    public static void closePage(BrowserContext context, Predicate<Page> condition) {
        try {
            List<Page> pages = context.pages();
            pages.stream().filter(condition).forEach(Page::close);
        } catch (Exception e) {
            log.error("closePage ==> ", e);
        }
    }

    public static void closePage(Page page) {
        try {
            page.close();
        } catch (Exception e) {
            log.error("closePage ==> ", e);
        }
    }

    public static void saveStorage(BrowserContext context, File storageFile) {
        if (isClosed(context) || storageFile == null) return;
        try {
            checkFile(storageFile);
            context.storageState(new BrowserContext.StorageStateOptions().setPath(storageFile.toPath()));
        } catch (Exception e) {
            log.error("saveStorage ==> ", e);
        }
    }

    private static void checkFile(File file) {
        if (file == null) return;
        if (!file.exists()) {
            try {
                checkDir(file.getParentFile());
                file.createNewFile();
            } catch (IOException e) {
                log.error("checkFile ==> ", e);
            }
        }
    }

    private static void checkDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static String safeText(Locator locator, String defaultText) {
        try {
            return locator.first().textContent();
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.warn("safeText ==> ", e);
        }
        return defaultText;
    }

    public static String safeText(Locator locator) {
        return safeText(locator, "");
    }

    public static String safeAttr(Locator locator, String attr, String defaultText) {
        try {
            return locator.first().getAttribute(attr);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.warn("safeAttr ==> ", e);
        }
        return defaultText;
    }

    public static String safeAttr(Locator locator, String attr) {
        return safeAttr(locator, attr, "");
    }

    public static boolean safeClick(Locator locator, Locator.ClickOptions options) {
        try {
            locator.first().click(options);
            return true;
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.warn("safeClick ==> ", e);
        }
        return false;
    }

    public static boolean safeClick(Locator locator) {
        return safeClick(locator, null);
    }

    public static boolean safeType(Locator locator, String text, Locator.TypeOptions options) {
        try {
            locator.first().type(text, options);
            return true;
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.warn("safeType ==> ", e);
        }
        return false;
    }

    public static boolean safeType(Locator locator, String text) {
        return safeType(locator, text, null);
    }

    public static boolean safeVisible(Locator locator, Locator.IsVisibleOptions options) {
        try {
            return locator.first().isVisible(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeVisible ==> ", e);
        }
        return false;
    }

    public static boolean safeVisible(Locator locator) {
        return safeVisible(locator, null);
    }

    public static boolean safeHidden(Locator locator, Locator.IsHiddenOptions options) {
        try {
            return locator.first().isHidden(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeHidden ==> ", e);
        }
        return false;
    }

    public static boolean safeHidden(Locator locator) {
        return safeHidden(locator, null);
    }

    public static boolean safeEnabled(Locator locator, Locator.IsEnabledOptions options) {
        try {
            return locator.first().isEnabled(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeEnabled ==> ", e);
        }
        return false;
    }

    public static boolean safeEnabled(Locator locator) {
        return safeEnabled(locator, null);
    }

    public static boolean safeDisable(Locator locator, Locator.IsDisabledOptions options) {
        try {
            return locator.first().isDisabled(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeDisable ==> ", e);
        }
        return false;
    }

    public static boolean safeDisable(Locator locator) {
        return safeDisable(locator, null);
    }

    public static boolean safeChecked(Locator locator, Locator.IsCheckedOptions options) {
        try {
            return locator.first().isChecked(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeChecked ==> ", e);
        }
        return false;
    }

    public static boolean safeChecked(Locator locator) {
        return safeChecked(locator, null);
    }

    public static boolean safeEditable(Locator locator, Locator.IsEditableOptions options) {
        try {
            return locator.first().isEditable(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeEditable ==> ", e);
        }
        return false;
    }

    public static boolean safeEditable(Locator locator) {
        return safeEditable(locator, null);
    }

    public static void safeClear(Locator locator, Locator.ClearOptions options) {
        try {
            locator.first().clear(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeClear ==> ", e);
        }
    }

    public static void safeClear(Locator locator) {
        safeClear(locator, null);
    }

    public static String safeHtml(Locator locator, Locator.InnerHTMLOptions options) {
        try {
            return locator.first().innerHTML(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeHtml ==> ", e);
        }
        return null;
    }

    public static String safeHtml(Locator locator) {
        return safeHtml(locator, null);
    }


    public static String safeInputValue(Locator locator, Locator.InputValueOptions options) {
        try {
            return locator.first().inputValue(options);
        } catch (TimeoutError ignored) {
        } catch (Exception e) {
            log.error("safeInputValue ==> ", e);
        }
        return null;
    }

    public static String safeInputValue(Locator locator) {
        return safeInputValue(locator, null);
    }

    public static boolean waitForLoadState(Page page, LoadState loadState, Page.WaitForLoadStateOptions options) {
        try {
            page.waitForLoadState(loadState, options);
            return true;
        } catch (TimeoutError e) {
            log.warn("等待超时 ==> ", e);
        }
        return false;
    }

    public static boolean waitForLoadState(Page page, LoadState loadState) {
        try {
            page.waitForLoadState(loadState, getWaitForLoadStateOptions());
            return true;
        } catch (TimeoutError e) {
            log.warn("等待超时 ==> ", e);
        }
        return false;
    }

    public static boolean waitForLoadState(Page page) {
        try {
            page.waitForLoadState(LoadState.LOAD, getWaitForLoadStateOptions());
            return true;
        } catch (TimeoutError e) {
            log.warn("等待超时 ==> ", e);
        }
        return false;
    }

    public static boolean reload(Page page) {
        return reload(page, null);
    }

    public static boolean reload(Page page, Page.ReloadOptions options) {
        try {
            page.reload(options);
            return true;
        } catch (PlaywrightException e) {
            log.warn("reload error ==> ", e);
        }
        return false;
    }

    public static boolean navigate(Page page, String url, int retry) {
        return navigate(page, url, null, retry);
    }

    public static boolean navigate(Page page, String url) {
        return navigate(page, url, 3);
    }

    public static boolean navigate(Page page, String url, Page.NavigateOptions options, int retry) {
        try {
            page.navigate(url, options);
            return true;
        } catch (PlaywrightException e) {
            log.warn("navigate occur error ==> ", e);
            retry = Math.max(0, retry);
            for (int i = 0; i < retry; i++) {
                // 模拟停止网页加载
                page.navigate("about:blank");
                log.warn("navigate retry {} times", retry);
                if (navigate(page, url, options, 0)) {
                    return true;
                }
            }
            if (retry > 0) {
                log.warn("navigate retry failed.");
            }
        }
        return false;
    }

    public static <T> T evaluate(Page page, String expression, Object arg) {
        try {
            return (T) page.evaluate(expression, arg);
        } catch (PlaywrightException e) {
            log.error("evaluate error ==> ", e);
            return null;
        }
    }

    public static <T> T evaluate(Page page, String expression) {
        return evaluate(page, expression, null);
    }

    public static JSHandle evaluateHandle(Page page, String expression, Object arg) {
        try {
            return page.evaluateHandle(expression, arg);
        } catch (PlaywrightException e) {
            log.error("evaluateHandle error ==> ", e);
            return null;
        }
    }

    public static JSHandle evaluateHandle(Page page, String expression) {
        return evaluateHandle(page, expression, null);
    }

    public static boolean isClosed(BrowserContext context) {
        if (context == null) return true;
        return doIsClosed(context);
    }

    public static boolean isClosed(Playwright playwright) {
        if (playwright == null) return true;
        return doIsClosed(playwright);
    }

    private static Field connectionFieldInChannelOwner;
    private static Field transportFieldInConnection;
    private static Field isClosedFieldInPipeTransport;
    private static Field isClosedOrClosingFieldInBrowserContextImpl;
    private static Field isConnectedFiledInBrowserImplClazz;
    private static Class<?> channelOwnerClazz;
    private static Class<?> browserContextImplClazz;
    private static Class<?> browserImplClazz;

    private static boolean doIsClosed(Object o) {
        try {
            if (channelOwnerClazz == null) {
                channelOwnerClazz = Class.forName("com.microsoft.playwright.impl.ChannelOwner");
            }
            if (o == null || !channelOwnerClazz.isAssignableFrom(o.getClass())) {
                throw new RuntimeException("object not support close, the object's type should be com.microsoft.playwright.impl.ChannelOwner.");
            }
            if (connectionFieldInChannelOwner == null) {
                connectionFieldInChannelOwner = channelOwnerClazz.getDeclaredField("connection");
                connectionFieldInChannelOwner.setAccessible(true);
            }
            Connection connection = (Connection) connectionFieldInChannelOwner.get(o);
            if (transportFieldInConnection == null) {
                transportFieldInConnection = connection.getClass().getDeclaredField("transport");
                transportFieldInConnection.setAccessible(true);
            }
            PipeTransport transport = (PipeTransport) transportFieldInConnection.get(connection);
            if (isClosedFieldInPipeTransport == null) {
                isClosedFieldInPipeTransport = transport.getClass().getDeclaredField("isClosed");
                isClosedFieldInPipeTransport.setAccessible(true);
            }
            if (o instanceof BrowserContext) {
                if (browserContextImplClazz == null) {
                    browserContextImplClazz = Class.forName("com.microsoft.playwright.impl.BrowserContextImpl");
                }
                if (isClosedOrClosingFieldInBrowserContextImpl == null) {
                    isClosedOrClosingFieldInBrowserContextImpl = browserContextImplClazz.getDeclaredField("isClosedOrClosing");
                    isClosedOrClosingFieldInBrowserContextImpl.setAccessible(true);
                }
                return isClosedFieldInPipeTransport.getBoolean(transport) || isClosedOrClosingFieldInBrowserContextImpl.getBoolean(o);
            }
            if (o instanceof Browser) {
                if (browserImplClazz == null) {
                    browserImplClazz = Class.forName("com.microsoft.playwright.impl.BrowserImpl");
                }
                if (isConnectedFiledInBrowserImplClazz == null) {
                    isConnectedFiledInBrowserImplClazz = browserImplClazz.getDeclaredField("isConnected");
                    isConnectedFiledInBrowserImplClazz.setAccessible(true);
                }
                return isClosedFieldInPipeTransport.getBoolean(transport) || isConnectedFiledInBrowserImplClazz.getBoolean(o);
            }
            return isClosedFieldInPipeTransport.getBoolean(transport);
        } catch (IllegalAccessException | NoSuchFieldException | ClassNotFoundException e) {
            throw new RuntimeException("check whether object is closed failed; occur some exceptions.", e);
        }
    }
}
