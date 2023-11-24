package com.example.slidercaptcha.shumei;

import com.example.slidercaptcha.Testable;
import com.example.slidercaptcha.utils.PwUtils;
import com.example.slidercaptcha.utils.TimeUtils;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;

/**
 * @Author 杨京三
 * @Date 2023/11/24 12:21
 * @Description
 **/
@Component("shumei-test")
@Slf4j
public class Test implements Testable {
    @Resource(name = "shumei-sliderHandler")
    private SliderHandler sliderHandler;

    @Value("${chromeExecutablePath:${user.home}\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe}")
    private String chromeExecutablePath;

    @SneakyThrows
    @Override
    public void test() {
        // storageFile用于保存cookie和localStorage
        File storageFile = new File("D:\\tmp\\storages\\storage-01.json");
        try (Browser browser = PwUtils.chromeBrowser();
             // with fake scripts
             BrowserContext context = PwUtils.defaultBrowserContext(browser, storageFile);
             Page page = context.newPage()) {
            PwUtils.maxWindow(page);
            PwUtils.navigate(page, "https://www.ishumei.com/new/product/tw/code");
            PwUtils.safeClick(page.locator("//*[@class=\"function\"]//*[text()=\"嵌入式\"]"));
            TimeUtils.sleep(2);
            int times = 0;
            while (times++ < 10) {
                log.debug("开始第{}次验证...", times);
                sliderHandler.handle(page);
                if (PwUtils.safeVisible(page.locator("//*[text()=\"验证成功\"]"))) {
                    break;
                }
                // refresh captcha
//                PwUtils.safeClick(page.locator(".shumei_captcha_img_refresh_btn.shumei_show"));
                TimeUtils.sleep(2);
            }
            log.info("验证成功");
            TimeUtils.sleep(2);
        } catch (Exception e) {
            log.error("", e);
        }
    }
}
