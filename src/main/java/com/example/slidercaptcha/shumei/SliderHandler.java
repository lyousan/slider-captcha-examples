package com.example.slidercaptcha.shumei;

import com.example.slidercaptcha.AbstractSliderHandler;
import com.example.slidercaptcha.utils.PwUtils;
import com.example.slidercaptcha.utils.TimeUtils;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.MouseButton;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Point;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * @Author 有三
 * @Date 2023/11/24 12:01
 * @Description 数美滑块验证码处理
 **/
@Component("shumei-sliderHandler")
@Slf4j
public class SliderHandler extends AbstractSliderHandler {


    /**
     * 处理滑动验证码
     */
    @SneakyThrows
    @Override
    public void handle(Page page) {
        log.debug("开始处理验证码...");
        String bgSrc = PwUtils.safeAttr(page.locator(".shumei_captcha_loaded_img_bg"), "src");
        String slideSrc = PwUtils.safeAttr(page.locator(".shumei_captcha_loaded_img_fg"), "src");
        File bgFile = downloadPic(bgSrc, "shumei-bg" + bgSrc.substring(bgSrc.lastIndexOf(".")));
        File slideFile = downloadPic(slideSrc, "shumei-slide" + slideSrc.substring(slideSrc.lastIndexOf(".")));
        if (bgFile == null || slideFile == null) {
            log.warn("下载验证码图片失败 bgSrc:{},slideSrc:{}", bgSrc, slideSrc);
            return;
        }
        // 提取
        slideFile = extractEffectivePart(slideFile);
        // 两种识别方式 new-marked.png old-marked.png，根据实际情况选择，效果其实差不多
        Point point = discernPosition(bgFile, slideFile);
        Point point1 = oldDiscernPosition(bgFile, slideFile, "old-marked.png");
        log.debug("识别点 ==> {}", point);
        // 缩放比例 0.5
        List<Integer> xlist = calcDistance((int) (point.x * 0.5), 0.2, 20);
        List<Integer> ylist = calcDistance(10, 0.1, 0);
        Mouse mouse = page.mouse();
        BoundingBox boundingBox = page.locator(".shumei_captcha_loaded_img_fg").boundingBox();
        double x = boundingBox.x + boundingBox.width / 2;
        double y = boundingBox.y + boundingBox.height / 2;
        log.debug("x:{},y:{}", x, y);
        mouse.move(x, y);
        mouse.down(new Mouse.DownOptions().setButton(MouseButton.LEFT));
        int ySize = ylist.size();
        for (int i = 0; i < xlist.size(); i++) {
            mouse.move(x += xlist.get(i), y += ylist.get(i >= ySize ? ySize - 1 : i));
            Thread.sleep(RANDOM.nextInt(50) + 30);
        }
        mouse.up();
        TimeUtils.sleep(2);
        log.debug("...处理验证码结束");
    }
}
