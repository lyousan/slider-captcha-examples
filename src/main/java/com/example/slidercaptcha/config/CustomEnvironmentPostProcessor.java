package com.example.slidercaptcha.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class CustomEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ApplicationHome h = new ApplicationHome(getClass());
        File jarFile = h.getSource();
        String jarPath = jarFile.getParentFile().getAbsolutePath();
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("jarPath", jarPath);
        MapPropertySource propertySource = new MapPropertySource("customProperty", propertyMap);
        environment.getPropertySources().addLast(propertySource);
    }
}
