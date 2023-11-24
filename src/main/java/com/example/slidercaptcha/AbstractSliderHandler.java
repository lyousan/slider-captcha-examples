package com.example.slidercaptcha;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import static org.opencv.imgcodecs.Imgcodecs.imread;
import static org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED;

/**
 * @Author 有三
 * @Date 2023/11/24 12:23
 * @Description
 **/
@Component
@Slf4j
public abstract class AbstractSliderHandler {

    @Value("${jarPath}")
    protected String jarPath;
    protected static File baseFolder;
    protected static String opencvModule;
    protected static final Random RANDOM = new Random(System.currentTimeMillis());

    @PostConstruct
    private void init() {
        baseFolder = new File(jarPath, "resources");
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            opencvModule = "libopencv_java455.so";
        } else if (osName.contains("windows")) {
            opencvModule = "opencv_java453.dll";
        } else if (osName.contains("mac")) {
            opencvModule = "libopencv_java470.dylib";
        }
        log.debug("加载opencv：{}/lib/{}", baseFolder, opencvModule);
        System.load(baseFolder.getAbsolutePath() + "/lib/" + opencvModule);
    }

    public abstract void handle(Page page);

    /**
     * 下载图片
     *
     * @param src      图片地址
     * @param fileName 保存名称
     */
    protected File downloadPic(String src, String fileName) {
        File result = new File(baseFolder, fileName);
        try {
            FileUtils.copyURLToFile(new URI(src).toURL(), result);
        } catch (IOException | URISyntaxException e) {
            log.warn("下载验证码图片失败：", e);
            return null;
        }
        return result;
    }

    /**
     * 提取图片的有效部分，保存到新的文件中
     * 图片四周的透明区域将会被去除
     *
     * @param inFile 带有透明通道的图片
     * @return 移除透明通道后的图片
     */
    protected File extractEffectivePart(File inFile) {
        File outFile = new File(inFile.getParent(), inFile.getName().replace(".png", "-handled.png"));
        Mat image = Imgcodecs.imread(inFile.getAbsolutePath(), Imgcodecs.IMREAD_UNCHANGED);
        Mat alphaChannel = new Mat();
        Core.extractChannel(image, alphaChannel, 3);
        Mat mask = new Mat();
        Core.compare(alphaChannel, new Scalar(0), mask, Core.CMP_EQ);
        Core.bitwise_not(mask, mask);
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        Rect boundingRect = Imgproc.boundingRect(contours.get(0));
        Mat croppedImage = image.submat(boundingRect);
        Imgcodecs.imwrite(outFile.getAbsolutePath(), croppedImage);
        return outFile;
    }

    /**
     * 识别位置
     *
     * @param bg   背景图/大图
     * @param temp 滑块图/模板图/小图
     * @return temp在bg中的坐标位置
     */
    protected Point discernPosition(File bg, File temp) {
        // 读取验证码图片
        Mat captcha = Imgcodecs.imread(bg.getAbsolutePath());
        // 将图像转换为灰度图像
        Mat gray = new Mat();
        Imgproc.cvtColor(captcha, gray, Imgproc.COLOR_BGR2GRAY);
        // 对图像进行阈值分割
        Mat binary = new Mat();
        Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
        // 进行形态学操作，去除噪声和填充空洞
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Imgproc.erode(binary, binary, kernel);
        Imgproc.dilate(binary, binary, kernel);
        // 读取滑块模板
        Mat template = Imgcodecs.imread(temp.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
        // 进行模板匹配，找到滑块位置
        Mat result = new Mat();
        Imgproc.matchTemplate(binary, template, result, Imgproc.TM_CCOEFF_NORMED);
        Core.MinMaxLocResult mmr = Core.minMaxLoc(result);
        log.debug("最大值坐标：{}", mmr.maxLoc);
        log.debug("最小值坐标：{}", mmr.minLoc);
        Point minLoc = mmr.minLoc;
        int sliderX = (int) minLoc.x;
        int sliderY = (int) minLoc.y;
        // 计算滑块的宽度和高度
        int sliderWidth = template.width();
        int sliderHeight = template.height();
        // 在图片上画一个红色矩形框，标注出滑块的位置
        Point topLeft = new Point(sliderX, sliderY);
        Point bottomRight = new Point(sliderX + sliderWidth, sliderY + sliderHeight);
        Scalar color = new Scalar(0, 0, 255); // 红色
        Imgproc.rectangle(captcha, topLeft, bottomRight, color, 2);
        // 保存标注后的图片
        Imgcodecs.imwrite(new File(bg.getParent(), "new-marked.png").getAbsolutePath(), captcha);
        return minLoc;
    }


    /**
     * 在源图中匹配模板图
     *
     * @param img          源图
     * @param temp         模板图
     * @param match_method 匹配的算法
     * @return 匹配到的结果
     */
    protected Mat matchTemplate(Mat img, Mat temp, int match_method) {
        int result_cols = img.cols() - temp.cols() + 1;
        int result_rows = img.rows() - temp.rows() + 1;
        Mat result = new Mat(result_rows, result_cols, CvType.CV_32FC1);
        Imgproc.matchTemplate(img, temp, result, match_method);
        return result;
    }

    /**
     * 模拟加速度滑动
     *
     * @param distance 位移距离
     * @param t        单位时间 推荐0.2
     * @param overflow 超出部分
     * @return 返回由单位时间内移动的位移组成的集合
     */
    protected List<Integer> calcDistance(Integer distance, double t, double overflow) {
        LinkedList<Integer> list = new LinkedList<>();
        // 变速阈值比例
        double thresholdScale = 0.6;
        int threshold = (int) ((distance + overflow) * thresholdScale);
        int a = 0;
        double v0 = 0;
        double v = v0;
        double x = 0;
        double x0 = x;
//        log.debug("开始计算正方向加速度...");
        while (x0 < distance + overflow) {
            if (x0 < threshold) {
                a = 20;
            } else {
                a = -20;
            }
            v = a * t;
            x = v0 * t + 0.5 * a * t * t;
            v0 += v;
            x0 += x;
            list.add((int) Math.round(x));
        }
//        log.debug("...计算正方向加速度完成");
        int total = list.stream().mapToInt(item -> item).sum();
        // 回收超出部分
        if (overflow > 0) {
//            log.debug("开始计算负方向加速度...");
            // total < 2 * distance 是为了防止出现死循环溢出
            while (total > distance && total < 2 * distance) {
                a = -20;
                v = a * t;
                x = v0 * t + 0.5 * a * t * t;
                v0 += v;
                total -= x;
                list.add(-(int) Math.round(x));
            }
//            log.debug("...计算负方向加速度完成");
        }
        total = list.stream().mapToInt(item -> item).sum();
        if (total > distance) {
            list.add(distance - total);
        } else {
            list.add(distance - total);
        }
        return list;
    }


    /**
     * 识别位置
     *
     * @param bg           背景图/大图
     * @param temp         滑块图/模板图/小图
     * @param markFileName 识别后附带标记的文件名，将会用红框标注识别位置
     * @return temp在bg中的坐标位置
     */
    protected Point oldDiscernPosition(File bg, File temp, String markFileName) {
        Mat big = imread(bg.getAbsolutePath());
        Mat small = imread(temp.getAbsolutePath());
        Mat result = matchTemplate(big, small, TM_CCOEFF_NORMED);
        Core.MinMaxLocResult loc = Core.minMaxLoc(result);
        log.debug("最大值坐标：{}", loc.maxLoc);
        log.debug("最小值坐标：{}", loc.minLoc);
        Point maxLoc = loc.maxLoc;
        int sliderX = (int) maxLoc.x;
        int sliderY = (int) maxLoc.y;
        // 计算滑块的宽度和高度
        int sliderWidth = small.width();
        int sliderHeight = small.height();
        // 在图片上画一个红色矩形框，标注出滑块的位置
        org.opencv.core.Point topLeft = new org.opencv.core.Point(sliderX, sliderY);
        org.opencv.core.Point bottomRight = new org.opencv.core.Point(sliderX + sliderWidth, sliderY + sliderHeight);
        Scalar color = new Scalar(0, 0, 255); // 红色
        Imgproc.rectangle(big, topLeft, bottomRight, color, 2);
        // 保存标注后的图片
        Imgcodecs.imwrite(new File(bg.getParent(), markFileName).getAbsolutePath(), big);
        return maxLoc;
    }
}
