package com.example.slidercaptcha.controller;

import com.example.slidercaptcha.Testable;
import com.example.slidercaptcha.utils.SpringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author 杨京三
 * @Date 2023/11/24 15:53
 * @Description
 **/
@RestController
@Slf4j
public class TestController {
    @GetMapping("/test/{code}")
    public String test(@PathVariable("code") String code) {
        try {
            SpringUtils.getBean(code + "-test", Testable.class).test();
            return "ok";
        } catch (NoSuchBeanDefinitionException e) {
            return code + " is not yet supported.";
        } catch (Exception e) {
            log.error("test error", e);
            return e.getMessage();
        }
    }

}
